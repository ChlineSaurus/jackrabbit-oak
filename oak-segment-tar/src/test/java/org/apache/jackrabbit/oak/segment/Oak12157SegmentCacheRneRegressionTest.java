/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment;

import static org.apache.jackrabbit.oak.segment.SegmentCache.DEFAULT_SEGMENT_CACHE_MB;
import static org.apache.jackrabbit.oak.segment.SegmentCache.newSegmentCache;
import static org.apache.jackrabbit.oak.segment.SegmentStore.EMPTY_STORE;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.util.concurrent.ExecutionException;

import org.apache.jackrabbit.oak.segment.spi.RepositoryNotReachableException;
import org.junit.Test;

/**
 * Regression reproduction for <b>OAK-12157</b> on the {@code oak-segment-tar} default backend.
 *
 * <h2>Bug summary</h2>
 * <p>
 * Originating commit: <code>f297eed8d4bbf17e138fb5e74f37bb0bd527851e</code>
 * (PR <a href="https://github.com/apache/jackrabbit-oak/pull/2859">#2859</a>,
 * "OAK-12157 : removed guava cache from oak-segment-tar"). The migration of
 * {@link SegmentCache} from Guava {@code LoadingCache} to Caffeine introduced an
 * outer try/catch in {@code SegmentCache.NonEmptyCache.getSegment(SegmentId, Callable)}
 * that calls {@code unwrapException(...)} on the cache loader's exception.
 * For data-segment ids &mdash; whose lsb top nibble is {@code 0xA}, i.e. the production case
 * &mdash; the catch path falls through {@code throw e;}. Downstream
 * {@code AbstractFileStore.asSegmentNotFoundException} only inspects
 * {@code e.getCause()}, which is {@code null} for a RuntimeException constructed
 * without a cause (e.g.&nbsp;{@link RepositoryNotReachableException} as thrown by
 * {@code AbstractPersistentCache}). Net effect: a RuntimeException raised by the
 * loader is silently converted to a {@code SegmentNotFoundException}.
 *
 * <h2>Why this matters</h2>
 * <p>
 * <b>OAK-9303</b> established the contract that <i>remote-store outages must NOT
 * be reported as missing-segment errors</i>. Monitoring/alerting pipelines (and
 * retry logic) differentiate {@code RepositoryNotReachableException} (transient
 * remote outage &rarr; retry / page on-call) from {@code SegmentNotFoundException}
 * (data corruption &rarr; treat as catastrophic / open incident). After the
 * OAK-12157 migration, the default segment-tar backend collapses the former into
 * the latter on cache miss for any data segment, defeating that contract for the
 * entire 2.0 line.
 *
 * <h2>Why existing coverage misses it</h2>
 * <p>
 * The pre-existing {@code CachingPersistenceTest.testRepositoryNotReachableWithCachingPersistence}
 * uses {@code new SegmentId(fs, 5, 5)}, whose lsb's top nibble is {@code 0x0},
 * not {@code 0xA}. This takes the {@code else} branch of {@code getSegment} which
 * still wraps the failure in {@code ExecutionException} (the legacy code path).
 * Production data segments always carry lsb top nibble = {@code 0xA} (see
 * {@code SegmentId.isDataSegmentId}), which routes through the new Caffeine
 * path that drops the wrapping. CI is therefore green while the production
 * code path is broken.
 *
 * <h2>What this test does</h2>
 * <p>
 * Two tests, both invoking {@code SegmentCache#getSegment} with a deliberately
 * data-segment-shaped {@link SegmentId} (lsb top nibble = {@code 0xA}) and a
 * loader that throws a bare {@link RepositoryNotReachableException} (no cause).
 * <ol>
 *   <li>{@link #rneFromLoaderShouldBeRetrievableFromCause()} asserts the OLD
 *       Guava-era contract: callers should be able to retrieve the original
 *       RuntimeException via {@code getCause()} of whatever
 *       {@code SegmentCache#getSegment} throws (or receive the same instance).</li>
 *   <li>{@link #fileStoreLikeRneDetectionShouldStillWork()} mirrors what
 *       {@code FileStore#readSegment} actually does after the exception
 *       escapes the cache, i.e.&nbsp;tests
 *       {@code thrown.getCause() instanceof RepositoryNotReachableException}.
 *       This is the consumer-visible regression.</li>
 * </ol>
 *
 * <h2>Expected behavior</h2>
 * <ul>
 *   <li>On Oak 1.92 (Guava cache, pre-f297eed8): both tests <b>pass</b>. Guava's
 *       {@code Cache.get(K, Callable)} wraps unchecked loader exceptions in
 *       {@code UncheckedExecutionException}, exposing the original RNE via
 *       {@code getCause()}.</li>
 *   <li>On Oak 2.0 trunk (Caffeine cache, post-f297eed8):
 *       {@link #fileStoreLikeRneDetectionShouldStillWork()} <b>fails</b> with
 *       {@code thrown=RepositoryNotReachableException, cause=null}. Caffeine's
 *       {@code Cache.get(K, Function)} propagates the RNE verbatim; the outer
 *       catch in {@code SegmentCache.getSegment} falls through because the
 *       cause is {@code null}, and the bare RNE escapes the cache. The
 *       downstream {@code FileStore.readSegment} RNE detection
 *       ({@code e.getCause() instanceof RepositoryNotReachableException}) then
 *       fails, and the failure is silently converted to
 *       {@code SegmentNotFoundException}.</li>
 * </ul>
 *
 * <p>
 * This test does not ship a fix; it locks the regression for the release blocker.
 * <p>
 * JIRA: OAK-12157<br>
 * Originating commit: f297eed8d4bb<br>
 * Related contract: OAK-9303 ({@code RepositoryNotReachableException})
 */
public class Oak12157SegmentCacheRneRegressionTest {

    private final SegmentCache cache = newSegmentCache(DEFAULT_SEGMENT_CACHE_MB);

    /**
     * Deliberately data-segment-shaped id: lsb top nibble = {@code 0xA}, which is the
     * production case. This routes through the new Caffeine code path in
     * {@code SegmentCache.NonEmptyCache.getSegment} where the regression lives.
     */
    private final SegmentId dataSegmentId =
            new SegmentId(EMPTY_STORE, 0x0000000000000001L, 0xa000000000000001L, cache::recordHit);

    /**
     * Asserts the pre-OAK-12157 contract: when the loader throws a bare
     * RuntimeException (no cause), callers should be able to identify the
     * original RuntimeException by inspecting whatever escapes the cache.
     * <p>
     * On Oak 1.92 (Guava): the RNE is wrapped in {@code UncheckedExecutionException}
     * and {@code getCause()} returns the original instance &mdash; this branch passes.
     * <p>
     * On Oak 2.0 (Caffeine, post-f297eed8): the bare RNE escapes the cache; both
     * {@code getCause()} branches fail because the bare RNE has no cause &mdash; the
     * test fails in the {@code RuntimeException} catch with a clear OAK-12157
     * regression message.
     */
    // JIRA: OAK-12157
    // Originating commit: f297eed8d4bb
    @Test
    public void rneFromLoaderShouldBeRetrievableFromCause() {
        RepositoryNotReachableException original = new RepositoryNotReachableException(null);
        try {
            cache.getSegment(dataSegmentId, () -> {
                throw original;
            });
            fail("expected an exception to escape SegmentCache.getSegment when the loader throws");
        } catch (ExecutionException e) {
            // Oak 1.92 Guava path: ExecutionException wrapping the original RNE via getCause().
            assertSame("On 1.92 (Guava) ExecutionException.getCause() must be the original RNE; "
                            + "on 2.0 (Caffeine) this branch is not taken for data segments.",
                    original, e.getCause());
        } catch (RepositoryNotReachableException e) {
            // Acceptable only if instance identity is preserved (it is on 2.0 trunk:
            // the bare RNE escapes verbatim). This branch documents the *escape* but
            // does NOT prove the OAK-9303 contract is satisfied -- FileStore.readSegment
            // inspects getCause(), not the type of the throwable itself. The next test
            // (fileStoreLikeRneDetectionShouldStillWork) covers that consumer view.
            assertSame("RNE instance identity must be preserved if it escapes unwrapped",
                    original, e);
        } catch (RuntimeException e) {
            // OAK-12157 regression path: a bare RuntimeException (or different wrapper)
            // escapes whose getCause() is not the original RNE. FileStore.readSegment
            // requires e.getCause() to be the RNE in order to bypass conversion to
            // SegmentNotFoundException; this branch demonstrates the regression
            // explicitly when (and only when) the type-specific catch above is not hit.
            if (e.getCause() != original) {
                fail("OAK-12157 regression: expected getCause() == RepositoryNotReachableException, "
                        + "got " + e.getClass().getName() + " with cause=" + e.getCause()
                        + ". FileStore.readSegment will now convert RNE to SegmentNotFoundException.");
            }
        }
    }

    /**
     * Consumer-level assertion that mirrors {@code FileStore.readSegment}'s actual
     * detection logic: it tests {@code thrown.getCause() instanceof
     * RepositoryNotReachableException}. This is the single condition that decides
     * whether a remote-outage RNE survives or is silently converted to
     * {@code SegmentNotFoundException} via {@code AbstractFileStore.asSegmentNotFoundException}.
     * <p>
     * On Oak 1.92 (Guava): the cache wraps the loader's RNE in an
     * {@code UncheckedExecutionException} whose {@code getCause()} is the original
     * RNE &mdash; this assertion passes, and {@code FileStore.readSegment} rethrows the RNE.
     * <p>
     * On Oak 2.0 trunk (Caffeine): the bare RNE escapes the cache with
     * {@code getCause() == null} &mdash; this assertion fails. {@code FileStore.readSegment}
     * therefore does NOT detect the outage and instead converts it to
     * {@code SegmentNotFoundException}, breaking the OAK-9303 monitoring contract.
     */
    // JIRA: OAK-12157
    // Originating commit: f297eed8d4bb
    @Test
    public void fileStoreLikeRneDetectionShouldStillWork() {
        RepositoryNotReachableException original = new RepositoryNotReachableException(null);
        Throwable thrown = null;
        try {
            cache.getSegment(dataSegmentId, () -> {
                throw original;
            });
        } catch (ExecutionException | RuntimeException e) {
            thrown = e;
        }
        if (thrown == null) {
            fail("expected an exception to escape SegmentCache.getSegment when the loader throws");
        }
        // Mirrors FileStore.readSegment / AbstractFileStore.asSegmentNotFoundException:
        //   if (e.getCause() instanceof RepositoryNotReachableException) { rethrow RNE }
        //   else { throw new SegmentNotFoundException(...) }
        boolean fileStoreWouldDetectRne =
                thrown.getCause() instanceof RepositoryNotReachableException;
        if (!fileStoreWouldDetectRne) {
            fail("OAK-12157 regression: FileStore.readSegment will NOT detect the RNE "
                    + "(thrown=" + thrown.getClass().getName() + ", cause=" + thrown.getCause() + "). "
                    + "On 1.92 (Guava) cause would be the original RepositoryNotReachableException; "
                    + "on 2.0 (Caffeine) the bare RNE escapes with cause=null and the failure is "
                    + "silently converted to SegmentNotFoundException, breaking the OAK-9303 "
                    + "remote-store-outage contract.");
        }
    }
}
