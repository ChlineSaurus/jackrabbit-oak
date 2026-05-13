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
package org.apache.jackrabbit.oak.benchmark;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;

import javax.jcr.Repository;

import org.apache.jackrabbit.oak.fixture.RepositoryFixture;
import org.apache.jackrabbit.oak.segment.Segment;
import org.apache.jackrabbit.oak.segment.SegmentCache;
import org.apache.jackrabbit.oak.segment.SegmentCache.SegmentCachePolicy;
import org.apache.jackrabbit.oak.segment.SegmentId;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.mockito.Mockito;

/**
 * Benchmark comparing CAFFEINE, LIRS, and GUAVA eviction policies inside
 * {@link SegmentCache} under three realistic AEM segment access scenarios.
 *
 * <p>All three policies go through the same {@code SegmentCache.NonEmptyCache}
 * code path; only the backing store differs.  This exercises the real
 * production code: load callbacks, weight tracking, eviction callbacks, and
 * L1/L2 memoisation.</p>
 *
 * <h3>Scenario A — Zipfian steady-state (timed run)</h3>
 * A small number of segments are extremely popular (templates, nav components)
 * and access probability decreases with rank.  Cache sized at ~10% of pool.
 * Favours frequency-aware policies (Caffeine W-TinyLFU).
 *
 * <h3>Scenario B — scan pollution (afterSuite)</h3>
 * A large sequential scan (GC traversal, index rebuild) precedes a Zipfian
 * workload.  The scan fills the TinyLFU frequency sketch with equal weights,
 * slowing post-scan re-admission of the true working set.
 *
 * <h3>Scenario C — cold-start regression (afterSuite)</h3>
 * A short scan below one TinyLFU decay period leaves scan entries at freq=1.
 * Working-set entries start at freq=0 and must beat the scan baseline to enter
 * main space.  Demonstrates the admission penalty in W-TinyLFU vs LRU.
 *
 * <h3>Scenario E — drifting working set (afterSuite)</h3>
 * An "active range" of {@code WIDTH_E} entries slides forward across a
 * larger pool; each entry is briefly hot then cold forever. Models the
 * AEM publish-tier shape produced by public crawlers / daily news cycle.
 * Caffeine's W-TinyLFU admission filter freezes the cache for the first
 * ~5 sketch-decay periods so the early-window miss rate is materially
 * worse than Guava LRU's. Per-epoch miss-rate output is printed so the
 * gap is visible — aggregate metrics hide it because the measure phase
 * runs long enough for the sketch to age out.
 *
 * <h3>Scenario F — drift-rate sweep (afterSuite)</h3>
 * Same shape as E but with a larger pool that never lets the cursor cap,
 * swept over multiple drift speeds plus a stationary control. Shows
 * Caffeine wins under no drift (its designed-for case) but loses by
 * 5–12 pp under continuous drift — the regression is steady-state, not
 * transient. Aggregate summary table prints Caffeine/Guava ratio per
 * drift rate.
 *
 * <p>Configurable via system properties:
 * <ul>
 *   <li>{@code -Dsegment.batch.size=1000} — accesses per {@code runTest()} call</li>
 *   <li>{@code -Dsegment.zipf.exponent=1.0} — Zipf exponent</li>
 *   <li>{@code -Dsegment.random.seed=42} — RNG seed for reproducibility</li>
 * </ul>
 */
public class SegmentCachePolicyBenchmark extends AbstractTest {

    // ----- cache sizing: segments vary 4–256 KB; avg ~130 KB; 130 MB gives ~1000 entries -----
    private static final int CACHE_SIZE_MB = 130;
    private static final int MIN_SEG_KB = 4;
    private static final int MAX_SEG_KB = 256;

    // ----- Scenario A pool -----
    private static final int TOTAL_SEGMENTS = 10_000;
    private static final double ZIPF_EXPONENT =
            Double.parseDouble(System.getProperty("segment.zipf.exponent", "1.0"));
    private static final long RANDOM_SEED = Long.getLong("segment.random.seed", 42L);
    private static final int BATCH_SIZE = Integer.getInteger("segment.batch.size", 1_000);

    // ----- Scenario B (scan then Zipfian) -----
    private static final int SCAN_LENGTH = 50_000;
    private static final int POST_SCAN_WARMUP = 20_000;
    private static final int POST_SCAN_MEASURE = 200_000;

    // ----- Scenario C (cold-start regression), scaled for ~1000-entry cache -----
    // Ratios match the original scenario: cache:scan:working-set = 1:9:3
    private static final int SCAN_C = 9_000;
    private static final int WORKING_SET_C = 3_000;
    private static final int MEASURE_C = 100_000;

    // ----- Scenario D (uniform random / cache thrash) -----
    // Pool is 25x cache capacity; uniform access means no hot data and ~95% miss rate.
    private static final int UNIFORM_POOL_D = 25_000;
    private static final int MEASURE_D = 200_000;

    // ----- Scenario E (drifting working set — the TMG production shape) -----
    // An "active range" of WIDTH_E entries slides forward across a 20K-entry
    // pool, shifting by 1 entry every DRIFT_E accesses. Within the active
    // range, access is Zipfian (exponent 0.5, mild hot/long-tail). Each
    // entry's lifetime in the active range is short and finite. After it
    // leaves, it is never accessed again — so it has freq=1..few in the
    // sketch and stays in cache only by recency. Caffeine's W-TinyLFU
    // freezes its main cache with whatever was hot at warmup time, since
    // newcomers cannot beat freq-aged incumbents (tie on freq → newcomer
    // rejected). Hit rate craters over time. Guava LRU tracks the slide.
    //
    // Width 1500 > cache cap ~1000 deliberately so the cache cannot hold
    // the entire active range — every policy is forced to choose.
    private static final int POOL_E   = 20_000;
    private static final int WIDTH_E  = 1_500;
    private static final int DRIFT_E  = 5;
    private static final int WARMUP_E = 50_000;
    private static final int MEASURE_E = 200_000;
    private static final double ZIPF_E_EXP = 0.5;
    private static final int EPOCH_OPS = 10_000;

    // ----- Scenario F (drift-rate sweep) -----
    // Same generator as E, but POOL_F is sized so the cursor never caps
    // for any drift in DRIFT_VARIANTS_F — guarantees continuous drift, no
    // transient. INF disables drift (stationary control where W-TinyLFU
    // is expected to win, validating the workload setup).
    private static final int POOL_F = 260_000;
    private static final int[] DRIFT_VARIANTS_F = {1, 5, 20, Integer.MAX_VALUE};

    private static final long DATA_SEG_LSB_MASK = 0xa000000000000000L;

    private static final SegmentCachePolicy[] POLICIES = {
        SegmentCachePolicy.CAFFEINE,
        SegmentCachePolicy.LIRS,
        SegmentCachePolicy.GUAVA
    };
    private static final String[] POLICY_NAMES = {"CAFFEINE", "LIRS", "GUAVA"};
    private static final int NUM_POLICIES = POLICIES.length;

    // ----- live Scenario A state -----
    private double[] zipfCdf;
    private Random rng;
    private SegmentCache[] liveCaches;
    private SegmentId[][] liveIds;
    private Segment[][] liveSegs;
    private LongAdder[] totalAccesses;

    @Override
    public String toString() {
        return "SegmentCachePolicyBenchmark";
    }

    /**
     * This benchmark exercises only in-memory caches; no JCR repository is used.
     */
    @Override
    protected Repository[] createRepository(RepositoryFixture fixture) throws Exception {
        return fixture.setUpCluster(1);
    }

    /**
     * Initialises one {@link SegmentCache} per policy with pre-built
     * {@link SegmentId} and mock {@link Segment} pools for Scenario A.
     */
    @Override
    protected void beforeSuite() {
        zipfCdf = buildZipfCdf(TOTAL_SEGMENTS, ZIPF_EXPONENT);
        rng = new Random(RANDOM_SEED);
        totalAccesses = new LongAdder[NUM_POLICIES];
        liveCaches = new SegmentCache[NUM_POLICIES];
        liveIds = new SegmentId[NUM_POLICIES][TOTAL_SEGMENTS];
        liveSegs = new Segment[NUM_POLICIES][TOTAL_SEGMENTS];
        for (int p = 0; p < NUM_POLICIES; p++) {
            totalAccesses[p] = new LongAdder();
            liveCaches[p] = SegmentCache.newSegmentCache(CACHE_SIZE_MB, POLICIES[p]);
            for (int i = 0; i < TOTAL_SEGMENTS; i++) {
                UUID uuid = UUID.randomUUID();
                long msb = uuid.getMostSignificantBits();
                long lsb = (uuid.getLeastSignificantBits() & 0x0fffffffffffffffL) | DATA_SEG_LSB_MASK;
                liveIds[p][i] = new SegmentId(
                        SegmentStore.EMPTY_STORE, msb, lsb,
                        liveCaches[p]::recordHit);
                int memUsage = MIN_SEG_KB * 1024 + rng.nextInt((MAX_SEG_KB - MIN_SEG_KB) * 1024);
                liveSegs[p][i] = Mockito.mock(Segment.class, Mockito.withSettings().stubOnly());
                Mockito.when(liveSegs[p][i].getSegmentId()).thenReturn(liveIds[p][i]);
                Mockito.when(liveSegs[p][i].estimateMemoryUsage()).thenReturn(memUsage);
            }
        }
    }

    /**
     * Performs {@code segment.batch.size} Zipfian accesses against all three
     * caches simultaneously.  The same segment rank is presented to every
     * policy per iteration so comparisons are fair.
     */
    @Override
    protected void runTest() throws Exception {
        for (int i = 0; i < BATCH_SIZE; i++) {
            int segIdx = zipfSample(zipfCdf, rng.nextDouble());
            accessAll(segIdx);
        }
    }

    private void accessAll(int segIdx) throws ExecutionException {
        for (int p = 0; p < NUM_POLICIES; p++) {
            Segment seg = liveSegs[p][segIdx];
            liveCaches[p].getSegment(liveIds[p][segIdx], () -> seg);
            totalAccesses[p].increment();
        }
    }

    /**
     * Prints a three-scenario comparison table.  Scenario A uses the live
     * counters from the AbstractTest loop; Scenarios B and C run fresh caches.
     */
    @Override
    protected void afterSuite() {
        int avgWeight = 32 + (MIN_SEG_KB + MAX_SEG_KB) / 2 * 1024;
        int cacheCapacity = (int) ((long) CACHE_SIZE_MB * 1024 * 1024 / avgWeight);
        System.out.printf(
                "%nSegmentCachePolicyBenchmark  cacheCapacity~=%d  pool=%d  zipf=%.1f%n%n",
                cacheCapacity, TOTAL_SEGMENTS, ZIPF_EXPONENT);

        System.out.println("--- Scenario A: Zipfian steady-state (AbstractTest timed run) ---");
        for (int p = 0; p < NUM_POLICIES; p++) {
            long misses = liveCaches[p].getCacheStats().getMissCount();
            long total = totalAccesses[p].sum();
            long evictions = liveCaches[p].getCacheStats().getEvictionCount();
            printResult(POLICY_NAMES[p], total - misses, misses, evictions);
        }

        System.out.printf(
                "%n--- Scenario B: scan (%,d segs) then Zipfian"
                        + " (warmup=%,d  measure=%,d ops) ---%n",
                SCAN_LENGTH, POST_SCAN_WARMUP, POST_SCAN_MEASURE);
        for (int p = 0; p < NUM_POLICIES; p++) {
            PolicySetup setup = freshSetup(p, POLICIES[p], TOTAL_SEGMENTS);
            long[] r = runScanThenZipf(setup);
            printResult(POLICY_NAMES[p], r[0], r[1], r[2]);
        }

        System.out.printf(
                "%n--- Scenario C: cold-start regression"
                        + " (scan=%,d  working-set=%,d  measure=%,d ops) ---%n",
                SCAN_C, WORKING_SET_C, MEASURE_C);
        System.out.println(
                "  scan fills TinyLFU sketch at freq=1;"
                        + " working-set entries start at freq=0");
        for (int p = 0; p < NUM_POLICIES; p++) {
            PolicySetup setup = freshSetup(p, POLICIES[p], SCAN_C + WORKING_SET_C);
            long[] r = runColdStart(setup);
            printResult(POLICY_NAMES[p], r[0], r[1], r[2]);
        }

        System.out.printf(
                "%n--- Scenario D: uniform random / cache thrash"
                        + " (pool=%,d = ~%dx cache  measure=%,d ops) ---%n",
                UNIFORM_POOL_D, UNIFORM_POOL_D / cacheCapacity, MEASURE_D);
        System.out.println(
                "  no hot data — uniform access over pool 25x cache; expected miss ~95%%");
        for (int p = 0; p < NUM_POLICIES; p++) {
            PolicySetup setup = freshSetup(p, POLICIES[p], UNIFORM_POOL_D);
            long[] r = runUniformRandom(setup);
            printResult(POLICY_NAMES[p], r[0], r[1], r[2]);
        }

        System.out.printf(
                "%n--- Scenario E: drifting working set — the TMG production shape%n"
                        + "    (pool=%,d  width=%,d  drift=1 entry / %d access"
                        + "  warmup=%,d  measure=%,d) ---%n",
                POOL_E, WIDTH_E, DRIFT_E, WARMUP_E, MEASURE_E);
        System.out.println(
                "  active range slides forward; each entry briefly hot then"
                        + " cold forever (freq decays only by Caffeine sketch aging)");
        for (int p = 0; p < NUM_POLICIES; p++) {
            PolicySetup setup = freshSetup(p, POLICIES[p], POOL_E);
            long[] r = runDriftingWindow(setup, POLICY_NAMES[p]);
            printResult(POLICY_NAMES[p], r[0], r[1], r[2]);
        }

        System.out.printf(
                "%n--- Scenario F: drift-rate sweep (continuous drift, no cursor cap)%n"
                        + "    (pool=%,d  width=%,d  warmup=%,d  measure=%,d) ---%n",
                POOL_F, WIDTH_E, WARMUP_E, MEASURE_E);
        System.out.println(
                "  INF = no drift (stationary baseline — W-TinyLFU's design case).");
        double[][] aggF = new double[DRIFT_VARIANTS_F.length][NUM_POLICIES];
        for (int d = 0; d < DRIFT_VARIANTS_F.length; d++) {
            int drift = DRIFT_VARIANTS_F[d];
            String label = (drift == Integer.MAX_VALUE) ? "INF" : String.valueOf(drift);
            System.out.printf("%n  drift = %s accesses/cursor%n", label);
            for (int p = 0; p < NUM_POLICIES; p++) {
                PolicySetup setup = freshSetup(p, POLICIES[p], POOL_F);
                long[] r = runDriftSweep(setup, drift);
                long total = r[0] + r[1];
                aggF[d][p] = (total > 0) ? 100.0 * r[1] / total : 0;
                printResult(POLICY_NAMES[p], r[0], r[1], r[2]);
            }
        }
        System.out.printf("%n  Summary: aggregate miss%% by drift × policy%n");
        System.out.println(
                "    drift   CAFFEINE   LIRS    GUAVA    Caff/Guava");
        for (int d = 0; d < DRIFT_VARIANTS_F.length; d++) {
            int drift = DRIFT_VARIANTS_F[d];
            String label = (drift == Integer.MAX_VALUE) ? "INF" : String.valueOf(drift);
            double caff = aggF[d][0], lirs = aggF[d][1], guava = aggF[d][2];
            System.out.printf("    %-6s  %6.1f%%  %6.1f%%  %6.1f%%   %5.2fx%n",
                    label, caff, lirs, guava, guava > 0 ? caff / guava : 0);
        }
    }

    /** Miss-rate column headers for the AbstractTest output row. */
    @Override
    protected String[] statsNames() {
        return new String[]{"  Caff_miss%", "  LIRS_miss%", "  Guav_miss%"};
    }

    /** Format strings for the three miss-rate columns. */
    @Override
    protected String[] statsFormats() {
        return new String[]{"  %10.1f", "  %10.1f", "  %10.1f"};
    }

    /** Current running miss-rate (%) for each policy from the live Scenario A run. */
    @Override
    protected Object[] statsValues() {
        Object[] vals = new Object[NUM_POLICIES];
        for (int p = 0; p < NUM_POLICIES; p++) {
            long misses = liveCaches[p].getCacheStats().getMissCount();
            long total = totalAccesses[p].sum();
            vals[p] = total == 0 ? 0.0 : 100.0 * misses / total;
        }
        return vals;
    }

    // -----------------------------------------------------------------------
    // PolicySetup helper
    // -----------------------------------------------------------------------

    /**
     * Groups a {@link SegmentCache} with its associated {@link SegmentId} and
     * mock {@link Segment} arrays for use in scenario runners.
     */
    private static final class PolicySetup {
        final SegmentCache cache;
        final SegmentId[] ids;
        final Segment[] segs;

        PolicySetup(SegmentCache cache, SegmentId[] ids, Segment[] segs) {
            this.cache = cache;
            this.ids = ids;
            this.segs = segs;
        }

        void access(int idx) {
            Segment s = segs[idx];
            try {
                cache.getSegment(ids[idx], () -> s);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Builds a fresh {@link PolicySetup} with {@code n} mock segments.
     *
     * @param policyIndex unused — kept for call-site readability
     * @param policy      the cache eviction policy to use
     * @param n           number of distinct segments to create
     */
    private static PolicySetup freshSetup(int policyIndex, SegmentCachePolicy policy, int n) {
        SegmentCache cache = SegmentCache.newSegmentCache(CACHE_SIZE_MB, policy);
        SegmentId[] ids = new SegmentId[n];
        Segment[] segs = new Segment[n];
        Random r = new Random(RANDOM_SEED);
        for (int i = 0; i < n; i++) {
            UUID uuid = UUID.randomUUID();
            long msb = uuid.getMostSignificantBits();
            long lsb = (uuid.getLeastSignificantBits() & 0x0fffffffffffffffL) | DATA_SEG_LSB_MASK;
            ids[i] = new SegmentId(
                    SegmentStore.EMPTY_STORE, msb, lsb,
                    cache::recordHit);
            int memUsage = MIN_SEG_KB * 1024 + r.nextInt((MAX_SEG_KB - MIN_SEG_KB) * 1024);
            segs[i] = Mockito.mock(Segment.class, Mockito.withSettings().stubOnly());
            Mockito.when(segs[i].getSegmentId()).thenReturn(ids[i]);
            Mockito.when(segs[i].estimateMemoryUsage()).thenReturn(memUsage);
        }
        return new PolicySetup(cache, ids, segs);
    }

    // -----------------------------------------------------------------------
    // Scenario runners
    // -----------------------------------------------------------------------

    /**
     * Scenario B: sequential scan then Zipfian workload.
     *
     * @return [hits, misses, evictions] measured only during the post-scan phase
     */
    private static long[] runScanThenZipf(PolicySetup setup) {
        double[] cdf = buildZipfCdf(TOTAL_SEGMENTS, ZIPF_EXPONENT);
        Random r = new Random(RANDOM_SEED);

        for (int i = 0; i < SCAN_LENGTH; i++) {
            setup.access(i % TOTAL_SEGMENTS);
        }
        for (int i = 0; i < POST_SCAN_WARMUP; i++) {
            setup.access(zipfSample(cdf, r.nextDouble()));
        }

        long missesBase = setup.cache.getCacheStats().getMissCount();
        long evictBase = setup.cache.getCacheStats().getEvictionCount();

        for (int i = 0; i < POST_SCAN_MEASURE; i++) {
            setup.access(zipfSample(cdf, r.nextDouble()));
        }

        long misses = setup.cache.getCacheStats().getMissCount() - missesBase;
        long evictions = setup.cache.getCacheStats().getEvictionCount() - evictBase;
        return new long[]{POST_SCAN_MEASURE - misses, misses, evictions};
    }

    /**
     * Scenario C: short scan below one TinyLFU decay period, then access the
     * working set with no warmup.
     *
     * @return [hits, misses, evictions]
     */
    private static long[] runColdStart(PolicySetup setup) {
        Random r = new Random(RANDOM_SEED);

        for (int i = 0; i < SCAN_C; i++) {
            setup.access(i);
        }

        long missesBase = setup.cache.getCacheStats().getMissCount();
        long evictBase = setup.cache.getCacheStats().getEvictionCount();

        for (int i = 0; i < MEASURE_C; i++) {
            setup.access(SCAN_C + r.nextInt(WORKING_SET_C));
        }

        long misses = setup.cache.getCacheStats().getMissCount() - missesBase;
        long evictions = setup.cache.getCacheStats().getEvictionCount() - evictBase;
        return new long[]{MEASURE_C - misses, misses, evictions};
    }

    /**
     * Scenario D: uniform random access over a pool far larger than the cache.
     * Warms the cache with one random pass (so each policy starts from a full
     * cache), then measures steady-state miss rate.
     *
     * @return [hits, misses, evictions]
     */
    private static long[] runUniformRandom(PolicySetup setup) {
        Random r = new Random(RANDOM_SEED);
        int n = setup.ids.length;

        // fill the cache before measuring
        for (int i = 0; i < n; i++) {
            setup.access(r.nextInt(n));
        }

        long missesBase = setup.cache.getCacheStats().getMissCount();
        long evictBase  = setup.cache.getCacheStats().getEvictionCount();

        for (int i = 0; i < MEASURE_D; i++) {
            setup.access(r.nextInt(n));
        }

        long misses    = setup.cache.getCacheStats().getMissCount()     - missesBase;
        long evictions = setup.cache.getCacheStats().getEvictionCount() - evictBase;
        return new long[]{MEASURE_D - misses, misses, evictions};
    }

    /**
     * Scenario E: drifting working set — the AEM publish-tier production
     * shape. An active range of {@code WIDTH_E} entries slides forward by
     * one entry every {@code DRIFT_E} accesses across a {@code POOL_E}
     * pool. Within the range, access is mildly Zipfian; once an entry
     * leaves the range it is never accessed again.
     *
     * <p>For Caffeine's W-TinyLFU this is the worst case: each newcomer
     * has sketch freq=1 and ties with sketch-aged incumbents, so the
     * tie-break (newcomer rejected) freezes main with whatever was hot
     * during warmup. The cache only starts admitting fresh entries after
     * ~5 sketch-decay periods (~50K accesses at cap≈1000). Guava LRU has
     * no admission filter and tracks the slide.
     *
     * <p>Per-epoch miss-rate is printed so the early-window penalty is
     * visible; aggregate metrics hide it because the measure phase runs
     * long enough for Caffeine to recover.
     *
     * @return [hits, misses, evictions] over the measure phase
     */
    private static long[] runDriftingWindow(PolicySetup setup, String policyLabel) {
        double[] cdf = buildZipfCdf(WIDTH_E, ZIPF_E_EXP);
        Random r = new Random(RANDOM_SEED);
        int n = setup.ids.length;
        int cursor = 0;
        long opsCount = 0;

        // Warmup — slide is active during warmup, so the warmup phase ends
        // with the cache in a representative steady state for each policy.
        for (int i = 0; i < WARMUP_E; i++) {
            int idx = cursor + zipfSample(cdf, r.nextDouble());
            if (idx >= n) {
                idx = n - 1;
            }
            setup.access(idx);
            opsCount++;
            if (opsCount % DRIFT_E == 0 && cursor + WIDTH_E < n) {
                cursor++;
            }
        }

        long missesBase = setup.cache.getCacheStats().getMissCount();
        long evictBase = setup.cache.getCacheStats().getEvictionCount();

        int numEpochs = MEASURE_E / EPOCH_OPS;
        System.out.printf("    %-10s  per-epoch miss%% (each = %,d ops):%n    ",
                policyLabel, EPOCH_OPS);
        long lastMisses = missesBase;
        for (int epoch = 0; epoch < numEpochs; epoch++) {
            for (int i = 0; i < EPOCH_OPS; i++) {
                int idx = cursor + zipfSample(cdf, r.nextDouble());
                if (idx >= n) {
                    idx = n - 1;
                }
                setup.access(idx);
                opsCount++;
                if (opsCount % DRIFT_E == 0 && cursor + WIDTH_E < n) {
                    cursor++;
                }
            }
            long cur = setup.cache.getCacheStats().getMissCount();
            System.out.printf("%5.1f ", 100.0 * (cur - lastMisses) / EPOCH_OPS);
            lastMisses = cur;
            if ((epoch + 1) % 10 == 0 && epoch < numEpochs - 1) {
                System.out.println();
                System.out.print("    ");
            }
        }
        System.out.println();

        long misses = setup.cache.getCacheStats().getMissCount() - missesBase;
        long evictions = setup.cache.getCacheStats().getEvictionCount() - evictBase;
        return new long[]{MEASURE_E - misses, misses, evictions};
    }

    /**
     * Scenario F: same workload generator as Scenario E but with {@code drift}
     * as a parameter (so the caller can sweep) and no per-epoch printing.
     * {@code drift == Integer.MAX_VALUE} disables drift entirely.
     *
     * @return [hits, misses, evictions] over the measure phase
     */
    private static long[] runDriftSweep(PolicySetup setup, int drift) {
        double[] cdf = buildZipfCdf(WIDTH_E, ZIPF_E_EXP);
        Random r = new Random(RANDOM_SEED);
        int n = setup.ids.length;
        int cursor = 0;
        long opsCount = 0;

        for (int i = 0; i < WARMUP_E; i++) {
            int idx = cursor + zipfSample(cdf, r.nextDouble());
            if (idx >= n) {
                idx = n - 1;
            }
            setup.access(idx);
            opsCount++;
            if (cursor + WIDTH_E < n && opsCount % drift == 0) {
                cursor++;
            }
        }

        long missesBase = setup.cache.getCacheStats().getMissCount();
        long evictBase = setup.cache.getCacheStats().getEvictionCount();

        for (int i = 0; i < MEASURE_E; i++) {
            int idx = cursor + zipfSample(cdf, r.nextDouble());
            if (idx >= n) {
                idx = n - 1;
            }
            setup.access(idx);
            opsCount++;
            if (cursor + WIDTH_E < n && opsCount % drift == 0) {
                cursor++;
            }
        }

        long misses = setup.cache.getCacheStats().getMissCount() - missesBase;
        long evictions = setup.cache.getCacheStats().getEvictionCount() - evictBase;
        return new long[]{MEASURE_E - misses, misses, evictions};
    }

    // -----------------------------------------------------------------------
    // Zipfian distribution
    // -----------------------------------------------------------------------

    /**
     * Pre-computes a cumulative Zipfian CDF over {@code n} items.
     * Item at rank 0 has weight 1/1^exponent, rank 1 has 1/2^exponent, etc.
     */
    private static double[] buildZipfCdf(int n, double exponent) {
        double[] cdf = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 1.0 / Math.pow(i + 1, exponent);
            cdf[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cdf[i] /= sum;
        }
        return cdf;
    }

    /** Samples a rank from the Zipfian CDF using binary search. */
    private static int zipfSample(double[] cdf, double u) {
        int lo = 0, hi = cdf.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cdf[mid] < u) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static void printResult(String label, long hits, long misses, long evictions) {
        long total = hits + misses;
        double missRate = total == 0 ? 0.0 : 100.0 * misses / total;
        double evictRate = total == 0 ? 0.0 : 100.0 * evictions / total;
        System.out.printf(
                "  %-12s  miss%%=%5.1f  hits=%,8d  misses=%,8d  evictions=%,8d  evict%%=%5.1f%n",
                label, missRate, hits, misses, evictions, evictRate);
    }
}
