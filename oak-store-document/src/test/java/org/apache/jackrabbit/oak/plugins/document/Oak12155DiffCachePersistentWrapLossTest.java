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
package org.apache.jackrabbit.oak.plugins.document;

// JIRA: OAK-12155
// Originating commit: 6107884f (PR #2848, merged with no human review)

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

import org.apache.jackrabbit.oak.cache.CacheValue;
import org.apache.jackrabbit.oak.cache.api.Cache;
import org.apache.jackrabbit.oak.plugins.document.persistentCache.PersistentCacheStats;
import org.apache.jackrabbit.oak.plugins.document.util.RevisionsKey;
import org.apache.jackrabbit.oak.plugins.document.util.StringValue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression test for <b>OAK-12155</b>.
 *
 * <h2>Background</h2>
 *
 * <p>Up to and including Oak 1.92, {@code DocumentNodeStoreBuilder.buildMemoryDiffCache()}
 * and {@code DocumentNodeStoreBuilder.buildLocalDiffCache()} delegated to the
 * shared {@code buildCache(CacheType, ...)} overload:</p>
 *
 * <pre>
 * public Cache&lt;CacheValue, StringValue&gt; buildMemoryDiffCache() {
 *     return buildCache(CacheType.DIFF, getMemoryDiffCacheSize(), null, null);
 * }
 * public Cache&lt;RevisionsKey, LocalDiffCache.Diff&gt; buildLocalDiffCache() {
 *     return buildCache(CacheType.LOCAL_DIFF, getLocalDiffCacheSize(), null, null);
 * }
 * </pre>
 *
 * <p>That overload runs the result through
 * {@code PersistentCache.wrap(getJournalCache(), ...)}, which:</p>
 * <ol>
 *   <li>wraps the in-memory cache in a package-private {@code NodeCache} that
 *       pages diff entries through the on-disk MapDB-backed
 *       <i>journal</i>/<i>diff</i> cache, so warm diffs survive process restarts;</li>
 *   <li>publishes a {@link PersistentCacheStats} entry under the cache-type
 *       name (e.g. {@code DIFF}, {@code LOCAL_DIFF}) so the
 *       {@code Document-MemoryDiff-persistent} and
 *       {@code Document-LocalDiff-persistent} JMX MBeans are registered for
 *       monitoring.</li>
 * </ol>
 *
 * <h2>Regression introduced by OAK-12155</h2>
 *
 * <p>The 2.0 commit rewrites both methods to construct the cache directly via
 * {@code org.apache.jackrabbit.oak.cache.api.CacheBuilder} (a Caffeine-backed
 * adapter), bypassing {@code buildCache(CacheType, ...)} entirely. As a
 * result, on every default-configured Document NodeStore deployment:</p>
 * <ul>
 *   <li>the on-disk diff-cache file is created (because
 *       {@code DocumentNodeStoreService.DEFAULT_JOURNAL_CACHE = "diff-cache"}
 *       remains active) but never populated or read;</li>
 *   <li>after a restart the in-memory diff cache is cold and external diffs
 *       fall through to {@code JournalDiffLoader.call()}, scanning
 *       {@code JournalEntry} documents from MongoDB. Backend load on the
 *       {@code JOURNAL} collection rises in proportion to traffic that
 *       crosses revision boundaries (observation, indexing, replication,
 *       package install);</li>
 *   <li>the {@code Document-MemoryDiff-persistent} and
 *       {@code Document-LocalDiff-persistent} MBeans disappear, silently
 *       breaking dashboards/alerts.</li>
 * </ul>
 *
 * <p>The regression is silent: the new code path runs without errors and
 * the diff cache still works in-memory, so nothing surfaces in tests or
 * logs. It only manifests as elevated journal-collection load and missing
 * monitoring data in production.</p>
 *
 * <h2>Interpreting the test outcome</h2>
 *
 * <p>This test wires the builder the way
 * {@code DocumentNodeStoreService.activate()} does in production with default
 * configuration ({@code setPersistentCache(...)} +
 * {@code setJournalCache(...)}) and asserts the wrapping behaviour that held
 * before OAK-12155.</p>
 *
 * <ul>
 *   <li><b>Pre-OAK-12155 (Oak 1.92):</b> all three assertions pass. Both diff
 *       caches are returned as the package-private {@code NodeCache} wrapper
 *       and {@code DIFF} / {@code LOCAL_DIFF} entries are present in
 *       {@code getPersistenceCacheStats()}.</li>
 *   <li><b>Post-OAK-12155 (current 2.0 trunk):</b> all three assertions fail.
 *       Both methods return a raw
 *       {@code org.apache.jackrabbit.oak.cache.impl.caffeine.CaffeineCacheAdapter}
 *       and the persistent-cache stats map is empty. The failure messages
 *       describe exactly what would be true on 1.92 versus what is true on
 *       2.0.</li>
 * </ul>
 *
 * <p>The fix is a small, contained restoration of the
 * {@code buildCache(CacheType.DIFF, ...)} /
 * {@code buildCache(CacheType.LOCAL_DIFF, ...)} routing. The persistent-cache
 * machinery itself ({@code NodeCache}, {@code PersistentCache.wrap}) was
 * already migrated to {@code oak.cache.api.Cache} under OAK-12156, so the
 * Caffeine migration does not require bypassing this layer. Once the wrap
 * is restored the assertions below will pass and this test should remain in
 * place as a regression guard.</p>
 *
 * <p>Note: the {@code NodeCache} class is package-private in
 * {@code persistentCache}, so this test asserts the wrapper by fully-qualified
 * class name rather than {@code instanceof}. The semantic is identical.</p>
 */
public class Oak12155DiffCachePersistentWrapLossTest {

    private static final String NODE_CACHE_FQN =
            "org.apache.jackrabbit.oak.plugins.document.persistentCache.NodeCache";

    private File tmpDir;
    private DocumentNodeStoreBuilder<?> builder;

    @Before
    public void setUp() throws Exception {
        tmpDir = Files.createTempDirectory("oak-12155-").toFile();
        // Wire the persistent + journal cache exactly the way
        // DocumentNodeStoreService.activate() does in production with default config.
        builder = DocumentNodeStoreBuilder.newDocumentNodeStoreBuilder();
        builder.setPersistentCache(new File(tmpDir, "cache").getAbsolutePath() + ",size=128");
        builder.setJournalCache(new File(tmpDir, "diff-cache").getAbsolutePath() + ",size=128");
    }

    @After
    public void tearDown() {
        if (tmpDir != null && tmpDir.exists()) {
            deleteRecursively(tmpDir);
        }
    }

    /**
     * The memory diff cache must be wrapped in {@code NodeCache} so writes
     * and reads page through the on-disk MapDB store. On 1.92 the result is
     * a {@code NodeCache}; on 2.0 trunk it is a raw {@code CaffeineCacheAdapter}.
     */
    @Test
    public void memoryDiffCacheShouldBeWrappedByPersistentCache() {
        Cache<CacheValue, StringValue> cache = builder.buildMemoryDiffCache();
        assertNotNull(cache);
        assertTrue("buildMemoryDiffCache() must return a NodeCache "
                        + "(persistent diff-cache wrapper) when journalCache is configured. "
                        + "Got: " + cache.getClass().getName(),
                NODE_CACHE_FQN.equals(cache.getClass().getName()));
    }

    /**
     * Same expectation for the local diff cache. On 1.92 the result is a
     * {@code NodeCache}; on 2.0 trunk it is a raw {@code CaffeineCacheAdapter}.
     */
    @Test
    public void localDiffCacheShouldBeWrappedByPersistentCache() {
        Cache<RevisionsKey, LocalDiffCache.Diff> cache = builder.buildLocalDiffCache();
        assertNotNull(cache);
        assertTrue("buildLocalDiffCache() must return a NodeCache "
                        + "(persistent diff-cache wrapper) when journalCache is configured. "
                        + "Got: " + cache.getClass().getName(),
                NODE_CACHE_FQN.equals(cache.getClass().getName()));
    }

    /**
     * The {@code DIFF} / {@code LOCAL_DIFF} persistent-cache stats must be
     * registered, since the {@code Document-MemoryDiff-persistent} and
     * {@code Document-LocalDiff-persistent} MBeans depend on them. On 1.92
     * both keys are present; on 2.0 trunk the map is empty for diff caches.
     */
    @Test
    public void diffPersistentCacheStatsShouldBeRegistered() {
        // Force construction so any stats registration runs.
        builder.buildMemoryDiffCache();
        builder.buildLocalDiffCache();

        Map<String, PersistentCacheStats> stats = builder.getPersistenceCacheStats();
        assertNotNull(stats);
        assertTrue("PersistentCacheStats for DIFF must be registered "
                        + "(was registered by buildCache(CacheType.DIFF, ...) before OAK-12155). "
                        + "Found keys: " + stats.keySet(),
                stats.containsKey("DIFF"));
        assertTrue("PersistentCacheStats for LOCAL_DIFF must be registered "
                        + "(was registered by buildCache(CacheType.LOCAL_DIFF, ...) before OAK-12155). "
                        + "Found keys: " + stats.keySet(),
                stats.containsKey("LOCAL_DIFF"));
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        // Best-effort.
        f.delete();
    }
}
