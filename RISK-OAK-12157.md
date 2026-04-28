# Risk analysis: OAK-12157 (release blocker)

# OAK-12157: removed guava cache from oak-segment-tar
**Commit:** f297eed8d4bbf17e138fb5e74f37bb0bd527851e  **Risk:** 9/10  **Status at 2.0 tip:** unchanged (no follow-up; OAK-12161 cleanup was reverted in c909dc9f2a)  **Has toggle:** No

oak-segment-tar is the default NodeStore backend. SegmentCache sits on the hot read path: every cache miss for a data segment goes through `SegmentCache.NonEmptyCache.getSegment(SegmentId, Callable)`. The migration from Guava `LoadingCache` to Caffeine via the new oak Cache API rewires exception translation, removal-listener semantics, and stats sources.

## Suspected release-blocker issues

### RuntimeException-from-loader exception-translation regression (blocker)
**Production scenario:** A persistent cache layer (typically remote / cloud-backed segment store) throws `RepositoryNotReachableException` while loading a data segment. `FileStore.readSegment` is supposed to detect this via `e.getCause() instanceof RepositoryNotReachableException` and rethrow it (preserving "remote outage" signaling and the warn log). After this commit, the RNE escapes Caffeine unwrapped (Caffeine's `Cache.get(K, Function)` propagates RuntimeExceptions as-is, unlike Guava's `Cache.get(K, Callable)` which wraps them in `UncheckedExecutionException`). The new outer catch in `SegmentCache.getSegment` only re-wraps when `cause instanceof Exception && !(cause instanceof RuntimeException)`; with `cause == null` (RNE constructed without a cause), it falls through to `throw e;`. `FileStore.readSegment` then sees the bare RNE: `e.getCause() == null`, the RNE branch is skipped, and the failure is silently converted to `SegmentNotFoundException`. **Net effect: remote-store outages now manifest as "missing segment" errors, defeating the OAK-9303 RNE-distinct-error contract and altering the consumer-visible exception type.**

This is empirically confirmed — see the reproduction test
`oak-segment-tar/src/test/java/org/apache/jackrabbit/oak/segment/Oak12157SegmentCacheRneRegressionTest.java`. The companion test `fileStoreLikeRneDetectionShouldStillWork` fails on current trunk: thrown class is `RepositoryNotReachableException` and `getCause() == null`, so FileStore's RNE-detection branch is bypassed.

**Files:**
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/SegmentCache.java:155-190` — new try/catch wrapping `cache.get(id, k -> ...)` ; the inner `if (e instanceof RuntimeException) throw (RuntimeException) e;` propagates RuntimeExceptions unwrapped, and the outer `catch (RuntimeException e)` only re-wraps when the cause is a checked exception.
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/file/FileStore.java:519-530` — consumer relying on `e.getCause() instanceof RepositoryNotReachableException`.
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/file/AbstractFileStore.java:181-186` — `asSegmentNotFoundException` only inspects `e.getCause()`, not `e` itself.
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/spi/persistence/persistentcache/AbstractPersistentCache.java:87-91` — RNE is rethrown as-is from persistent cache loaders (existing OAK-9303 contract).

The existing test `CachingPersistenceTest.testRepositoryNotReachableWithCachingPersistence` does NOT cover this regression — it uses `new SegmentId(fs, 5, 5)` whose lsb fails `(lsb >>> 60) == 0xA`, taking the `else` branch of `getSegment` which still wraps in `ExecutionException`. Production data segments always have lsb top nibble = 0xA, hitting the broken Caffeine path.

**What to verify:**
1. Reproduce: run the bundled test (see "How to run the reproduction" below). Test 2 fails on current trunk.
2. Check existing remote-segment-store users (`oak-segment-azure`, `oak-segment-aws`, downstream cloud personas) for code paths that distinguish RNE vs SNE in alerting/retry logic.
3. Audit any other RuntimeException type that escapes the loader and was previously rebound via `getCause()` (e.g., `IllegalRepositoryStateException` ancestors, `SegmentNotFoundException` thrown deeper than top-level).

**Mitigation:** In `SegmentCache.getSegment`, after Caffeine returns, mimic Guava's wrapping: when a RuntimeException escapes the loader, rethrow it wrapped (e.g., `throw new UncheckedExecutionException(e)` or simply `throw new RuntimeException(e)` with the original as cause). Alternatively patch `FileStore.readSegment` and `ReadOnlyFileStore.readSegment` to also test `e instanceof RepositoryNotReachableException` directly. A cleanest fix: change the inner catch to always wrap (`throw new RuntimeException(e)` for both checked and unchecked) — that restores Guava's "always wrapped, original via getCause()" contract.

### Cache stats now use approximate `estimatedSize()` for MBean (high)
**Production scenario:** `SegmentCache.NonEmptyCache` constructs `new Stats(NAME, maximumWeight, cache::estimatedSize)` (line 139). `RecordCache.Default.size()` likewise returns `cache.estimatedSize()` (line 188). Caffeine's `estimatedSize()` is documented as "the value returned is an estimate; the actual count may differ if there are concurrent insertions or removals". For the existing Oak `CacheStatsMBean` contract, `getElementCount` was always exact. Monitoring dashboards and capacity-planning tooling that compute `weight/size` ratios may see transient impossibilities (e.g. divide-by-zero, ratios > maxWeight/avgWeight). Not data-corrupting but observability noise on the default backend.

**Files:**
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/SegmentCache.java:139` — `cache::estimatedSize`
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/RecordCache.java:188` — `cache.estimatedSize()`

**What to verify:**
1. Check whether any in-tree MBean assertion or Sling/AEM monitoring depends on exact element count.
2. If exactness matters for any caller, switch to `cache.asMap().size()` (Caffeine: exact, but slightly more expensive under contention).

**Mitigation:** Document the change in release notes; or use `cache.asMap().size()` in MBean stats accessors.

### `key.unloaded()` no longer null-checked (low — but flagged)
**Production scenario:** `onRemove` previously guarded `if (notification.getKey() != null) notification.getKey().unloaded();`. The new code drops the null-check: `key.unloaded();`. Caffeine's `RemovalListener` contract guarantees a non-null key for non-COLLECTED causes. With strong-reference SegmentId keys this is safe in practice. Listed as defense-in-depth only.

**Files:**
- `oak-segment-tar/src/main/java/org/apache/jackrabbit/oak/segment/SegmentCache.java:145-151`

**What to verify:** Caffeine RemovalCause.COLLECTED unreachable (no weak/soft refs configured). Confirmed by inspecting `CacheBuilder.configureCaffeineBuilder()` — no weak/soft key/value configuration is wired.

**Mitigation:** None required; document.

### Async eviction-listener race (defused)
**Initial concern:** Caffeine's `evictionListener` is async by default and could let `SegmentId.unloaded()` race with a concurrent `cache.get()` re-population. **Defused** by `CacheBuilder.configureCaffeineBuilder()` line 276: `caffeineBuilder.executor(Runnable::run)` — listener fires synchronously on the calling thread. Note this also means *every* Caffeine maintenance task (including refresh, expiration cleanup) now runs on the caller — minor latency tax on cache reads/writes vs. Guava's cleanup-on-write, but no correctness issue.

**Files:**
- `oak-core-spi/src/main/java/org/apache/jackrabbit/oak/cache/api/CacheBuilder.java:273-280`

## Verdict
**Confirmed release blocker.** The RNE→SNE exception-translation regression is reproducible (proof test fails on current trunk — see Test execution result below), affects the default backend, and silently degrades remote-segment-store error reporting for the entire 2.0 line. No feature toggle to disable. Existing test suite does not cover the data-segment-id branch of this code path, so CI is green. PR review evidence confirms the issue was **never raised or addressed** during review of #2859, and there is no follow-up commit on trunk.

Recommended action before tagging 2.0:
1. Patch `SegmentCache.getSegment` to wrap RuntimeException-from-loader in a wrapper exception with the original as cause (restoring Guava `UncheckedExecutionException` semantics), OR patch all `FileStore`/`ReadOnlyFileStore` callers to handle bare RNE.
2. Add the proof test (or equivalent) to the `oak-segment-tar` suite to lock the contract.

Secondary issues (estimatedSize MBean accuracy, removed `concurrencyLevel` hint) are not blockers but should be called out in 2.0 release notes since this directly mutates the default backend's behavior.

## How to run the reproduction
```bash
mvn -pl oak-segment-tar test -Dtest=Oak12157SegmentCacheRneRegressionTest -DfailIfNoTests=false
```
On current 2.0 trunk, `fileStoreLikeRneDetectionShouldStillWork` fails with
`thrown=RepositoryNotReachableException, cause=null`, demonstrating that
`FileStore.readSegment` will now silently convert the RNE into a
`SegmentNotFoundException`. The companion test `rneFromLoaderShouldBeRetrievableFromCause`
documents the same escape via instance-identity preservation.

## PR review evidence (PR #2859)
- PR #2859 ("OAK-12157 : removed guava cache from oak-segment-tar") was merged into `trunk` on 2026-04-16 with **zero human review comments and zero PR-level reviews**. The only feedback recorded by the GitHub API is from `sonarqubecloud[bot]` ("Quality Gate passed", 89.6% new-code coverage, 4 new issues, 0 accepted).
  - `curl .../pulls/2859/reviews` returns `[]` (empty).
  - `curl .../pulls/2859/comments` returns `[]` (empty inline review comments).
  - `curl .../issues/2859/comments` returns only the SonarQube bot comment.
- Keyword search (`RepositoryNotReachableException`, `RNE`, `SegmentNotFoundException`, `OAK-9303`, `cause`, `unwrap`, `Caffeine`) against the PR review/comments yields nothing — the exception-unwrapping change was **not discussed by any reviewer**.
- `git log --all --grep=OAK-12157` returns only the original commit `f297eed8d4`. No follow-up commit on `trunk` addresses the regression.
- `git log --all --grep=RepositoryNotReachable` shows the original work that established the contract is buried inside a squashed commit (`21429f3d19 Squashed commit of the following:`); the only OAK-9303 mention in `git log` is a cosmetic `0ea42704e4 OAK-9303 - fix svn:eol-style`. The contract `AbstractFileStore.asSegmentNotFoundException` (lines 181-186) and `AbstractPersistentCache` (the loader that throws RNE without a cause) are unchanged on trunk and still rely on `e.getCause()` inspection.
- Conclusion: there is **no evidence the regression was raised, considered, or resolved** during PR review. The PR was effectively merged unchecked from a behavioral-contract standpoint.

## Test execution result
**Path:** `oak-segment-tar/src/test/java/org/apache/jackrabbit/oak/segment/Oak12157SegmentCacheRneRegressionTest.java`
**Command:** `mvn -pl oak-segment-tar test -Dtest=Oak12157SegmentCacheRneRegressionTest -DfailIfNoTests=false`
**Outcome:** Reproduction test fails on current trunk as predicted (`fileStoreLikeRneDetectionShouldStillWork`).
**Output:**
```
[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.090 s <<< FAILURE! -- in org.apache.jackrabbit.oak.segment.Oak12157SegmentCacheRneRegressionTest
[ERROR] org.apache.jackrabbit.oak.segment.Oak12157SegmentCacheRneRegressionTest.fileStoreLikeRneDetectionShouldStillWork -- Time elapsed: 0.085 s <<< FAILURE!
java.lang.AssertionError: OAK-12157 regression: FileStore.readSegment will NOT detect the RNE
  (thrown=org.apache.jackrabbit.oak.segment.spi.RepositoryNotReachableException, cause=null).
  ...
[INFO] BUILD FAILURE
```
**Conclusion:** The hypothesised regression is empirically real on trunk (commit f297eed8d4 untouched). When a data segment loader (top-nibble lsb = 0xA) throws `RepositoryNotReachableException` with no cause, the bare RNE escapes `SegmentCache.getSegment` unwrapped (Caffeine's `Cache.get(K, Function)` propagates RuntimeExceptions verbatim, and the outer catch in `SegmentCache` falls through `throw e;` because `e.getCause() == null`). `FileStore.readSegment` and `AbstractFileStore.asSegmentNotFoundException` only inspect `e.getCause()`, so they never detect the RNE and silently convert it to a `SegmentNotFoundException`. This breaks the OAK-9303 contract and remote-store outage detection on the default backend, and there is no follow-up commit, no review discussion, and no feature toggle to mitigate it.
