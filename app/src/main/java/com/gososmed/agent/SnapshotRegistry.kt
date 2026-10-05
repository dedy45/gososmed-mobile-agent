package com.gososmed.agent

/**
 * In-memory LRU cache for active UI snapshots (PLAN-DETERMINISTIC-ANDROID-PORTAL.md).
 * Capacity: 5 snapshots, TTL: 30 seconds.
 */
object SnapshotRegistry {
    const val CAPACITY = 5
    const val DEFAULT_TTL_MS = 30_000L

    data class Entry(
        val snapshot: SnapshotResult,
        val registeredAtMs: Long
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>?): Boolean {
            return size > CAPACITY
        }
    }
    @Volatile
    private var latestSnapshotId: String? = null

    /**
     * Registers a captured snapshot into the cache.
     */
    fun register(snapshot: SnapshotResult, registeredAtMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            entries[snapshot.snapshotId] = Entry(snapshot, registeredAtMs)
            latestSnapshotId = snapshot.snapshotId
        }
    }

    /**
     * Retrieves snapshot by ID if present and not expired (within maxAgeMs).
     */
    fun get(
        snapshotId: String,
        maxAgeMs: Long = DEFAULT_TTL_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SnapshotResult? {
        synchronized(lock) {
            val entry = entries[snapshotId] ?: return null
            if (currentTimeMs - entry.registeredAtMs > maxAgeMs) {
                entries.remove(snapshotId)
                if (latestSnapshotId == snapshotId) {
                    latestSnapshotId = entries.keys.lastOrNull()
                }
                return null
            }
            return entry.snapshot
        }
    }

    /**
     * Returns the latest active snapshot, or null if empty / expired.
     */
    fun getLatest(
        maxAgeMs: Long = DEFAULT_TTL_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): SnapshotResult? {
        synchronized(lock) {
            val id = latestSnapshotId ?: entries.keys.lastOrNull() ?: return null
            return get(id, maxAgeMs, currentTimeMs)
        }
    }

    /**
     * Checks if a snapshot exists and has not expired.
     */
    fun isFresh(
        snapshotId: String,
        maxAgeMs: Long = DEFAULT_TTL_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(lock) {
            val entry = entries[snapshotId] ?: return false
            return (currentTimeMs - entry.registeredAtMs) <= maxAgeMs
        }
    }

    /**
     * Clears all registered snapshots (for testing or service reset).
     */
    fun clear() {
        synchronized(lock) {
            entries.clear()
            latestSnapshotId = null
        }
    }
}
