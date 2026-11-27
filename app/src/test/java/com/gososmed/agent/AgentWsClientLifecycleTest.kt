package com.gososmed.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AgentWsClientLifecycleTest {

    @Test
    fun `pending callback store evicts stale heartbeat and echo entries and clears on stop`() {
        val store = AgentWsClient.PendingCallbackStore()
        var invoked = false
        store.put(1L, nowMs = 1_000L) { invoked = true }
        store.put(2L, nowMs = 25_000L) { }
        assertEquals(2, store.size())

        val evicted = store.evictExpired(nowMs = 35_000L, ttlMs = 30_000L)
        assertEquals(1, evicted)
        assertEquals(1, store.size())
        assertNull(store.remove(1L))
        assertNotNull(store.remove(2L))
        assertEquals(0, store.size())

        store.put(3L, nowMs = 40_000L) { }
        store.clear()
        assertEquals(0, store.size())
        assertEquals(false, invoked)
    }
}
