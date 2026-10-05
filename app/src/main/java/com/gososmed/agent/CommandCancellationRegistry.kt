package com.gososmed.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe cancellation and deadline manager for commands
 * (PLAN-DETERMINISTIC-ANDROID-PORTAL.md Sections 5 & 9).
 */
object CommandCancellationRegistry {

    private data class CommandState(
        val deadlineAtMs: Long,
        @Volatile var cancelled: Boolean = false
    )

    private val activeCommands = ConcurrentHashMap<String, CommandState>()

    /**
     * Registers a command with its deadline in milliseconds from now.
     * Returns true if newly registered, false if already active (duplicate in-flight request).
     */
    fun register(
        requestId: String,
        deadlineMs: Long,
        currentTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (requestId.isBlank()) return false
        val safeDeadline = if (deadlineMs <= 0L) 10000L else deadlineMs
        val deadlineAtMs = if (safeDeadline > Long.MAX_VALUE - currentTimeMs) {
            Long.MAX_VALUE
        } else {
            currentTimeMs + safeDeadline
        }
        val existing = activeCommands.putIfAbsent(requestId, CommandState(deadlineAtMs = deadlineAtMs, cancelled = false))
        return existing == null
    }

    /**
     * Cancels an active command. Returns true if the command was found and active.
     */
    fun cancel(requestId: String): Boolean {
        val state = activeCommands[requestId] ?: return false
        state.cancelled = true
        return true
    }

    /**
     * Checks if command is marked as cancelled.
     */
    fun isCancelled(requestId: String): Boolean {
        return activeCommands[requestId]?.cancelled ?: false
    }

    /**
     * Checks if command has expired its deadline.
     */
    fun isExpired(requestId: String, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val state = activeCommands[requestId] ?: return false
        return currentTimeMs > state.deadlineAtMs
    }
    /**
     * Checks if a command is currently registered and active.
     */
    fun isRegistered(requestId: String): Boolean {
        return activeCommands.containsKey(requestId)
    }


    /**
     * Removes command from registry when execution finishes.
     */
    fun unregister(requestId: String) {
        activeCommands.remove(requestId)
    }

    /**
     * Clears all commands (useful for testing or service resets).
     */
    fun clear() {
        activeCommands.clear()
    }
}
