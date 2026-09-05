package com.gososmed.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test pertama repo agent (Plan 07 P1-1): jalur murni-JVM dari
 * HierarchySerializer — tanpa AccessibilityNodeInfo (null) — harus tetap
 * menghasilkan output XML valid untuk parser Go `internal/mobile.ParseHierarchy`.
 */
class HierarchySerializerTest {

    @Test
    fun `dump dengan root null menghasilkan hierarchy kosong`() {
        val out = HierarchySerializer.dump(null)
        assertEquals("<hierarchy rotation=\"0\">\n</hierarchy>\n", out)
    }

    @Test
    fun `summarize dengan root null menghasilkan objek kosong`() {
        assertEquals(0, HierarchySerializer.summarize(null).length())
    }
}
