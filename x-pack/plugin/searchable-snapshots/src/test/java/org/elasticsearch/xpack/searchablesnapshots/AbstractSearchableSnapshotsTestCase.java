/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.searchablesnapshots;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.lucene.store.ESIndexInputTestCase;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.indices.recovery.SearchableSnapshotRecoveryState;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.threadpool.ThreadPoolStats;
import org.elasticsearch.xpack.searchablesnapshots.cache.CacheService;
import org.junit.After;
import org.junit.Before;

import java.util.concurrent.TimeUnit;

public abstract class AbstractSearchableSnapshotsTestCase extends ESIndexInputTestCase {

    protected ThreadPool threadPool;

    @Before
    public void setUpTest() {
        threadPool = new TestThreadPool(getTestName(), SearchableSnapshots.executorBuilders());
    }

    @After
    public void tearDownTest() {
        assertTrue(ThreadPool.terminate(threadPool, 30L, TimeUnit.SECONDS));
    }

    /**
     * @return a new {@link CacheService} instance configured with default settings
     */
    protected CacheService defaultCacheService() {
        return new CacheService(AbstractSearchableSnapshotsTestCase::noOpCacheCleaner, Settings.EMPTY);
    }

    /**
     * @return a new {@link CacheService} instance configured with random cache size and cache range size settings
     */
    protected CacheService randomCacheService() {
        final Settings.Builder cacheSettings = Settings.builder();
        if (randomBoolean()) {
            cacheSettings.put(CacheService.SNAPSHOT_CACHE_SIZE_SETTING.getKey(), randomCacheSize());
        }
        if (randomBoolean()) {
            cacheSettings.put(CacheService.SNAPSHOT_CACHE_RANGE_SIZE_SETTING.getKey(), randomCacheRangeSize());
        }
        return new CacheService(AbstractSearchableSnapshotsTestCase::noOpCacheCleaner, cacheSettings.build());
    }

    /**
     * @return a new {@link CacheService} instance configured with the given cache size and cache range size settings
     */
    protected CacheService createCacheService(final ByteSizeValue cacheSize, final ByteSizeValue cacheRangeSize) {
        return new CacheService(
            AbstractSearchableSnapshotsTestCase::noOpCacheCleaner,
            Settings.builder()
                .put(CacheService.SNAPSHOT_CACHE_SIZE_SETTING.getKey(), cacheSize)
                .put(CacheService.SNAPSHOT_CACHE_RANGE_SIZE_SETTING.getKey(), cacheRangeSize)
                .build()
        );
    }

    protected static void noOpCacheCleaner() {}

    /**
     * @return a random {@link ByteSizeValue} that can be used to set {@link CacheService#SNAPSHOT_CACHE_SIZE_SETTING}.
     * Note that it can return a cache size of 0.
     */
    protected static ByteSizeValue randomCacheSize() {
        return new ByteSizeValue(randomNonNegativeLong());
    }

    /**
     * @return a random {@link ByteSizeValue} that can be used to set {@link CacheService#SNAPSHOT_CACHE_RANGE_SIZE_SETTING}
     */
    protected static ByteSizeValue randomCacheRangeSize() {
        return new ByteSizeValue(
            randomLongBetween(CacheService.MIN_SNAPSHOT_CACHE_RANGE_SIZE.getBytes(), CacheService.MAX_SNAPSHOT_CACHE_RANGE_SIZE.getBytes())
        );
    }

    protected static SearchableSnapshotRecoveryState createRecoveryState() {
        ShardRouting shardRouting = TestShardRouting.newShardRouting(
            new ShardId(randomAlphaOfLength(10), randomAlphaOfLength(10), 0),
            randomAlphaOfLength(10),
            true,
            ShardRoutingState.INITIALIZING,
            new RecoverySource.SnapshotRecoverySource(
                UUIDs.randomBase64UUID(),
                new Snapshot("repo", new SnapshotId(randomAlphaOfLength(8), UUIDs.randomBase64UUID())),
                Version.CURRENT,
                new IndexId("some_index", UUIDs.randomBase64UUID(random()))
            )
        );
        DiscoveryNode targetNode = new DiscoveryNode("local", buildNewFakeTransportAddress(), Version.CURRENT);
        SearchableSnapshotRecoveryState recoveryState = new SearchableSnapshotRecoveryState(shardRouting, targetNode, null);

        recoveryState.setStage(RecoveryState.Stage.INIT)
            .setStage(RecoveryState.Stage.INDEX)
            .setStage(RecoveryState.Stage.VERIFY_INDEX)
            .setStage(RecoveryState.Stage.TRANSLOG);
        recoveryState.getIndex().setFileDetailsComplete();
        recoveryState.setStage(RecoveryState.Stage.FINALIZE).setStage(RecoveryState.Stage.DONE);

        return recoveryState;
    }

    /**
     * Wait for all operations on the threadpool to complete
     */
    protected static void assertThreadPoolNotBusy(ThreadPool threadPool) throws Exception {
        assertBusy(() -> {
            for (ThreadPoolStats.Stats stat : threadPool.stats()) {
                assertEquals(stat.getActive(), 0);
                assertEquals(stat.getQueue(), 0);
            }
        }, 30L, TimeUnit.SECONDS);
    }
}
