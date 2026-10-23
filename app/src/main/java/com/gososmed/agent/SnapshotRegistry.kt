package com.gososmed.agent

/**
 * In-memory LRU cache for active UI snapshots (PLAN-DETERMINISTIC-ANDROID-PORTAL.md).
 * Capacity: 8 snapshots, TTL: 30 seconds.
 * Separate pinned-on-failure storage: Max 2 snapshots, TTL: 300 seconds.
 */
object SnapshotRegistry {
    const val CAPACITY = 8
    const val DEFAULT_TTL_MS = 30_000L
    const val MAX_PINNED = 2
    const val DEFAULT_PIN_TTL_MS = 300_000L // 5 minutes (system-design-feedback-orpv-2026-10-23.md §2.9.c)

    data class Entry(
        val snapshot: SnapshotResult,
        val registeredAtMs: Long,
        val pinTtlMs: Long? = null
    )

    private val lock = Any()
    private val entries = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>?): Boolean {
            return size > CAPACITY
        }
    }

    // Pinned storage is independent of regular LRU slots (system-design-feedback-orpv-2026-10-23.md §2.9.c)
    private val pinnedEntries = object : LinkedHashMap<String, Entry>(MAX_PINNED, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Entry>?): Boolean {
            return size > MAX_PINNED
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
            // Check pinned entries first with independent pin TTL
            val pinned = pinnedEntries[snapshotId]
            if (pinned != null) {
                val ttl = pinned.pinTtlMs ?: DEFAULT_PIN_TTL_MS
                if (currentTimeMs - pinned.registeredAtMs <= ttl) {
                    return pinned.snapshot
                } else {
                    pinnedEntries.remove(snapshotId)
                }
            }

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
     * Pins a snapshot in independent storage on failure or final receipt (max 2 global).
     */
    fun pin(
        snapshotId: String,
        pinTtlMs: Long = DEFAULT_PIN_TTL_MS,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(lock) {
            val entry = entries[snapshotId] ?: pinnedEntries[snapshotId] ?: return false
            pinnedEntries[snapshotId] = entry.copy(registeredAtMs = currentTimeMs, pinTtlMs = pinTtlMs)
            return true
        }
    }

    /**
     * Unpins a snapshot when trajectory settles or transitions to pending_receipt.
     */
    fun unpin(snapshotId: String): Boolean {
        synchronized(lock) {
            return pinnedEntries.remove(snapshotId) != null
        }
    }

    /**
     * Checks if a snapshot is currently pinned and not expired.
     */
    fun isPinned(snapshotId: String, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            val entry = pinnedEntries[snapshotId] ?: return false
            val ttl = entry.pinTtlMs ?: DEFAULT_PIN_TTL_MS
            if (currentTimeMs - entry.registeredAtMs > ttl) {
                pinnedEntries.remove(snapshotId)
                return false
            }
            return true
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
            pinnedEntries.clear()
            latestSnapshotId = null
        }
    }
}
