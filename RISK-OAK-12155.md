# Risk analysis: OAK-12155 (release blocker)

- **JIRA:** OAK-12155 — *removed guava cache from oak-store-document diff caches*
- **Originating commit:** `6107884fc1a6bcead8c8b916e6b646011a5eb0c8`
- **PR:** https://github.com/apache/jackrabbit-oak/pull/2848 (merged with zero human review)
- **Risk:** 8/10
- **Status at 2.0 tip:** Live, no follow-up.
- **Has feature toggle:** No

## Suspected release-blocker issues
- **Persistent (journal) diff cache silently disabled** (severity: blocker)
- **Single-flight loading lost on `MemoryDiffCache.getChanges`** (severity: high)

### Persistent (journal) diff cache silently disabled
**What could go wrong (production scenario):**
Before this commit:
```java
public Cache<CacheValue, StringValue> buildMemoryDiffCache() {
    return buildCache(CacheType.DIFF, getMemoryDiffCacheSize(), null, null);
}
public Cache<RevisionsKey, LocalDiffCache.Diff> buildLocalDiffCache() {
    return buildCache(CacheType.LOCAL_DIFF, getLocalDiffCacheSize(), null, null);
}
```
`buildCache(CacheType, …)` is the central wiring point that calls
`getJournalCache().wrap(…)` for `CacheType.DIFF` and `CacheType.LOCAL_DIFF`,
wrapping the in-memory cache with a `NodeCache` that pages diffs through MapDB
(the "diff-cache" persistent store). It also registers a
`PersistentCacheStats` MBean for monitoring.

After this commit:
```java
public Cache<CacheValue, StringValue> buildMemoryDiffCache() {
    return CacheBuilder.<CacheValue, StringValue>newBuilder()
            .maximumWeight(...).weigher(...).recordStats().build();
}
public Cache<RevisionsKey, LocalDiffCache.Diff> buildLocalDiffCache() { /* same */ }
```
Both methods now bypass `buildCache(CacheType, …)` and construct the cache
directly. The `PersistentCache.wrap(…)` call for `CacheType.DIFF` and
`CacheType.LOCAL_DIFF` is **never invoked**.

Defaults:
- `DocumentNodeStoreService.DEFAULT_JOURNAL_CACHE = "diff-cache"` — journal/persistent diff cache is **enabled by default**.
- `PersistentCache.cacheDiff = true` and `cacheLocalDiff = true` — DIFF and LOCAL_DIFF are wrapped by default.

Consequence on every production deployment that does not explicitly disable
`journalCache` (which is essentially all of them):
1. The on-disk diff cache file (`diff-cache/*`) is no longer populated or read by `MemoryDiffCache` / `LocalDiffCache`.
2. After a process restart, the in-memory diff cache is empty until re-warmed by traffic; previously a warm MapDB store survived restarts.
3. External revision diffs that previously hit the on-disk tier now fall through to `JournalDiffLoader.call()`, which scans `JournalEntry` documents from MongoDB. Backend load on the `JOURNAL` collection rises in proportion to traffic that crosses revision boundaries (observation listeners, search index update, package install, replication).
4. The `Document-MemoryDiff-persistent` and `Document-LocalDiff-persistent` MBeans (registered by `buildCache` via `persistentCacheStats.put(cacheType.name(), stats)`) are **no longer present**, breaking dashboards/alerts that watch them.

**Files to inspect:**
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/DocumentNodeStoreBuilder.java:988-1004` — direct construction, no `buildCache(CacheType…)` routing
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/DocumentNodeStoreBuilder.java:1080-1107` — the `buildCache(CacheType, …)` overload that does the persistent-cache wrapping (still works for NODE / CHILDREN / DOCUMENT / PREV_DOCUMENT)
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/persistentCache/PersistentCache.java:390-427` — `wrap(...)` switch on `CacheType.DIFF` / `LOCAL_DIFF`
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/DocumentNodeStoreService.java:159-160,371,540` — `DEFAULT_JOURNAL_CACHE = "diff-cache"`
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/persistentCache/PersistentCache.java:59-62` — `cacheDiff = true`, `cacheLocalDiff = true` defaults

**What to verify:**
1. Build a `DocumentNodeStoreBuilder` with `setPersistentCache(...)` and `setJournalCache(...)`, call `buildMemoryDiffCache()` and `buildLocalDiffCache()`, and assert the returned cache is **not** an instance of `NodeCache` (i.e., persistent wrapping skipped). See the regression test shipped with this branch.
2. Confirm `builder.getPersistenceCacheStats()` no longer contains entries for `DIFF` / `LOCAL_DIFF`. (Old code: `persistentCacheStats.put(cacheType.name(), stats)` ran for these; new code never enters that branch.)
3. Boot a `DocumentNodeStore` with default config, write a value, observe the `diff-cache` MapDB folder is created but never grows.
4. Verify the `Document-MemoryDiff-persistent` and `Document-LocalDiff-persistent` MBean names, if previously registered, are missing from MBeanServer at runtime.

**Mitigation if confirmed:** patch — restore the `buildCache(CacheType.DIFF, …)` / `buildCache(CacheType.LOCAL_DIFF, …)` routing. The `Cache<K,V>` returned by `buildCache(CacheType…)` already returns a `NodeCache` when `PersistentCache.wrap` is in effect. The Caffeine migration does not require bypassing this layer; the persistent-cache machinery (`NodeCache`, `PersistentCache.wrap`) was already updated to use `oak.cache.api.Cache` in OAK-12156. The fix is a ~10-line revert of the `buildMemoryDiffCache` / `buildLocalDiffCache` bodies.

### Single-flight loading lost on `MemoryDiffCache.getChanges`
**What could go wrong (production scenario):**
Old code used `Cache.get(key, Callable)` — Guava de-duplicates concurrent
loaders for the same key (single-flight). New code (`MemoryDiffCache.java:77-85`)
does:
```java
diff = diffCache.getIfPresent(key);
if (diff == null) {
    diff = isUnchanged(...) ? StringValue.EMPTY : new StringValue(loader.call());
    diffCache.asMap().putIfAbsent(key, diff);
}
```
N concurrent observation listeners / external diffs missing on the same
revision range key all execute `loader.call()` — which in production is
`JournalDiffLoader.call()` doing a Mongo journal scan. First-writer-wins in
`putIfAbsent`; subsequent writers throw away their (potentially expensive)
result.

The author's inline comment explains this is a deliberate workaround for
Caffeine's CHM-compute "Recursive update" exception (loader can re-enter
the same cache via `isChildUnchanged` and would deadlock under Caffeine's
per-key compute lock). The trade-off is correct (avoid livelock at the
expense of per-key dedup), but under concurrent traffic it can amplify
journal-collection load by 5-50x for hot revision ranges.

**Files to inspect:**
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/MemoryDiffCache.java:77-86`
- `oak-store-document/src/main/java/org/apache/jackrabbit/oak/plugins/document/JournalDiffLoader.java:66-91` — what each duplicate loader does (Mongo journal scan)

**What to verify:**
1. Concurrent test with N=16 threads calling `getChanges(from, to, path, journalLoader)` with the same key; count loader invocations. Pre-OAK-12155: 1. Post-OAK-12155: up to N.
2. Establish whether journal scan amplification is acceptable for typical AEM observation traffic.

**Mitigation if confirmed:** patch — use `ConcurrentMap.computeIfAbsent` on `diffCache.asMap()` for single-flight semantics. This is safe **only** if the recursive-update concern can be ruled out for the production loader (`JournalDiffLoader.call()`); inspection of that path shows it does not call back into `diffCache.getChanges` for the same cache (it appends to a `WrappedDiffCache` overlay instead of querying the in-memory cache), so `computeIfAbsent` should work. Alternative: keep the current behaviour and document the journal-scan amplification.

## Investigated and downgraded

### `loader.call()` returning null → NPE on `new StringValue(null)` (DOWNGRADED to non-issue)
- `DiffCache.Loader` has only two implementations: `JournalDiffLoader` (returns `wrappedCache.changes` which is initialised to `""` and never null) and the inline `DiffCache.Loader` in `DocumentNodeStore.compareAgainstBaseState` (returns `diffImpl(...)` which always sets `diff = w.toString()` — non-null).
- The previous Guava code path also called `new StringValue(loader.call())` inside the `Callable`, so it would have NPE'd identically. No behaviour change.

### Lost `getStats` integration with shared `StatisticsProvider` (DOWNGRADED to test-only metric surface change)
JMX MBean names "Document-MemoryDiff" and "Document-LocalDiff" are still registered (via `MemoryDiffCache.diffCacheStats` and `LocalDiffCache.diffCacheStats` passed through `DocumentNodeStoreService.registerCacheStatsMBean`). The names match the old behaviour. Only the persistent-cache `*-persistent` MBeans are missing, and that is captured under the persistent-cache regression above.

## Verdict
**Release-blocker.**

The persistent-cache integration loss is a real, default-on production
regression that affects every customer running with default journal-cache
settings (which is the standard AEM configuration). It causes:
- Loss of warm cross-restart diff caching
- Increased Mongo journal-collection scan load
- Disappearance of `*-persistent` JMX metrics that monitoring may depend on

The fix is small and contained (restore `buildCache(CacheType.DIFF, …)`
routing), so this should be resolved before 2.0 GA rather than shipping
and patching in 2.0.x.

The single-flight loss is high-severity but acceptable as-is given the
deliberate trade-off documented in the inline comment.

## How to run the reproduction

The regression test ships at:
`oak-store-document/src/test/java/org/apache/jackrabbit/oak/plugins/document/Oak12155DiffCachePersistentWrapLossTest.java`

Run it with:

```bash
mvn -pl oak-store-document test -Dtest=Oak12155DiffCachePersistentWrapLossTest -DfailIfNoTests=false
```

On the current 2.0 trunk all three assertions fail:

```
[INFO] Running org.apache.jackrabbit.oak.plugins.document.Oak12155DiffCachePersistentWrapLossTest
[ERROR] Tests run: 3, Failures: 3, Errors: 0, Skipped: 0

[ERROR] memoryDiffCacheShouldBeWrappedByPersistentCache
java.lang.AssertionError: buildMemoryDiffCache() must return a NodeCache
   (persistent diff-cache wrapper) when journalCache is configured.
   Got: org.apache.jackrabbit.oak.cache.impl.caffeine.CaffeineCacheAdapter

[ERROR] localDiffCacheShouldBeWrappedByPersistentCache
java.lang.AssertionError: buildLocalDiffCache() must return a NodeCache
   (persistent diff-cache wrapper) when journalCache is configured.
   Got: org.apache.jackrabbit.oak.cache.impl.caffeine.CaffeineCacheAdapter

[ERROR] diffPersistentCacheStatsShouldBeRegistered
java.lang.AssertionError: PersistentCacheStats for DIFF must be registered
   (was registered by buildCache(CacheType.DIFF, ...) before OAK-12155).
   Found keys: []
```

This matches the predicted regression exactly: with a fully-wired
`setPersistentCache(...)` + `setJournalCache(...)` (production default),
both `buildMemoryDiffCache()` and `buildLocalDiffCache()` return a raw
Caffeine adapter — never the `NodeCache` wrapper that pages diffs through
MapDB — and `getPersistenceCacheStats()` contains zero entries (no `DIFF`,
no `LOCAL_DIFF`), so the `*-persistent` MBeans are gone.

Once the `buildCache(CacheType.DIFF, ...)` / `buildCache(CacheType.LOCAL_DIFF, ...)`
routing is restored, all three assertions will pass and the test should
remain in place as a regression guard.

## PR review evidence (PR #2848)
- **PR title:** "OAK-12155 : removed guava cache from oak-store-document diff caches"
- **PR body:** *(empty / null)* — no author rationale, no migration notes, no mention of dropping the persistent-cache wrapper.
- **Commits:** Single commit `fb4c4b1c7e85c127d748dbb43cef5d4ce604c4a7` by `rishabhdaim`. No follow-up commits addressing reviewer feedback.
- **Reviews:** **Zero formal reviews.** `GET /pulls/2848/reviews` returns an empty array. Four `review_requested` events were fired by the author at 2026-04-13T15:05:48Z, but no reviewer ever submitted a review before merge.
- **Inline review comments:** **Zero.** `GET /pulls/2848/comments` returns an empty array.
- **General conversation comments:** Only the `sonarqubecloud[bot]` Quality-Gate-Passed comment ("100.0% Coverage on New Code"). No human comment on the PR thread at all.
- **Timeline:** Opened 2026-04-13T15:05; merged by the author themselves (`rishabhdaim`) at 2026-04-14T09:40:27Z — roughly 18.5 hours later, with no reviewer sign-off recorded.
- **No reviewer flagged the persistent-cache wrap loss.** Nobody pointed out that `buildMemoryDiffCache()` / `buildLocalDiffCache()` no longer route through `buildCache(CacheType, …)`. There is no statement from the author that the bypass was intentional.
- **No follow-up JIRA referenced** in the PR conversation or commits (e.g., no "addressed in OAK-XXXXX" note).

Summary: this PR was self-merged by the author without any human review, and the persistent-cache regression went unobserved. Nothing in the PR conversation downgrades the suspicion — if anything, the absence of review strengthens the concern.
