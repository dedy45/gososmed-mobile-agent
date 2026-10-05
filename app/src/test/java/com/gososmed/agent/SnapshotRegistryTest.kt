package com.gososmed.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SnapshotRegistryTest {

    @Before
    fun setup() {
        SnapshotRegistry.clear()
    }

    private fun createDummySnapshot(id: String, hash: String = "hash-$id"): SnapshotResult {
        return SnapshotResult(
            snapshotId = id,
            frameSeq = 1L,
            capturedAtMs = 1000L,
            display = SnapshotDisplay(0, 1080, 2400, 0, 420),
            foreground = SnapshotForeground("com.example.app", 1),
            treeHash = hash,
            screenFingerprint = "fp-$id",
            quality = SnapshotQuality(valid = true, invalidBoundsRatio = 0.0, windowChanged = false),
            nodes = listOf(
                SnapshotNode(
                    nodeId = "node-$id",
                    windowId = 1,
                    packageName = "com.example.app",
                    className = "android.widget.Button",
                    bounds = SnapshotBounds(10, 10, 100, 50),
                    clickable = true
                )
            )
        )
    }

    @Test
    fun testRegisterAndGetFreshSnapshot() {
        val snap = createDummySnapshot("snap-1")
        SnapshotRegistry.register(snap, registeredAtMs = 1000L)

        val retrieved = SnapshotRegistry.get("snap-1", maxAgeMs = 30000L, currentTimeMs = 5000L)
        assertNotNull(retrieved)
        assertEquals("snap-1", retrieved?.snapshotId)
        assertTrue(SnapshotRegistry.isFresh("snap-1", maxAgeMs = 30000L, currentTimeMs = 5000L))
    }

    @Test
    fun testStaleSnapshotLookupReturnsNull() {
        val retrieved = SnapshotRegistry.get("stale-or-unknown-id")
        assertNull(retrieved)
        assertFalse(SnapshotRegistry.isFresh("stale-or-unknown-id"))
    }

    @Test
    fun testExpiredSnapshotAfterTtlReturnsNull() {
        val snap = createDummySnapshot("snap-ttl")
        SnapshotRegistry.register(snap, registeredAtMs = 10_000L)

        // Lookup after default 30s TTL (at 40_001ms)
        val result = SnapshotRegistry.get("snap-ttl", maxAgeMs = SnapshotRegistry.DEFAULT_TTL_MS, currentTimeMs = 40_001L)
        assertNull(result)
        assertFalse(SnapshotRegistry.isFresh("snap-ttl", maxAgeMs = SnapshotRegistry.DEFAULT_TTL_MS, currentTimeMs = 40_001L))
    }

    @Test
    fun testExpiredSnapshotReturnsNullAndIsNotFresh() {
        val snap = createDummySnapshot("snap-old")
        SnapshotRegistry.register(snap, registeredAtMs = 1000L)

        // 31 seconds later (> 30000ms TTL)
        val retrieved = SnapshotRegistry.get("snap-old", maxAgeMs = 30000L, currentTimeMs = 32000L)
        assertNull(retrieved)
        assertFalse(SnapshotRegistry.isFresh("snap-old", maxAgeMs = 30000L, currentTimeMs = 32000L))
    }

    @Test
    fun testLruCapacityEviction() {
        // Capacity is 5; register 6 snapshots
        for (i in 1..6) {
            val snap = createDummySnapshot("snap-$i")
            SnapshotRegistry.register(snap, registeredAtMs = 1000L + i * 10L)
        }

        // snap-1 should have been evicted because capacity is 5
        assertNull(SnapshotRegistry.get("snap-1", currentTimeMs = 2000L))

        // snap-2 to snap-6 should be present
        for (i in 2..6) {
            assertNotNull(SnapshotRegistry.get("snap-$i", currentTimeMs = 2000L))
        }

        assertEquals("snap-6", SnapshotRegistry.getLatest(currentTimeMs = 2000L)?.snapshotId)
    }

    @Test
    fun testGetLatestReturnsMostRecent() {
        assertNull(SnapshotRegistry.getLatest())

        val snap1 = createDummySnapshot("snap-1")
        val snap2 = createDummySnapshot("snap-2")
        SnapshotRegistry.register(snap1, registeredAtMs = 1000L)
        assertEquals("snap-1", SnapshotRegistry.getLatest(currentTimeMs = 1000L)?.snapshotId)

        SnapshotRegistry.register(snap2, registeredAtMs = 2000L)
        assertEquals("snap-2", SnapshotRegistry.getLatest(currentTimeMs = 2000L)?.snapshotId)
    }

    @Test
    fun testClearRemovesAll() {
        SnapshotRegistry.register(createDummySnapshot("snap-1"))
        SnapshotRegistry.register(createDummySnapshot("snap-2"))
        SnapshotRegistry.clear()

        assertNull(SnapshotRegistry.get("snap-1"))
        assertNull(SnapshotRegistry.get("snap-2"))
        assertNull(SnapshotRegistry.getLatest())
    }
}
