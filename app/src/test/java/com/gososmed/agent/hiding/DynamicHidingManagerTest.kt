package com.gososmed.agent.hiding

import com.gososmed.agent.privileged.PrivilegedShell
import com.gososmed.agent.privileged.ShellResult
import com.gososmed.agent.privileged.ShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicHidingManagerTest {

    private class MockShell : PrivilegedShell {
        val executedCommands = mutableListOf<String>()
        val currentSettings = mutableMapOf<String, String>(
            "global/development_settings_enabled" to "1",
            "global/adb_enabled" to "1",
            "global/adb_wifi_enabled" to "1",
            "secure/accessibility_enabled" to "1",
            "secure/enabled_accessibility_services" to "com.gososmed.agent/com.gososmed.agent.AgentAccessibilityService"
        )

        override fun status() = ShellStatus(available = true, paired = true, connected = true, uid = 2000, error = "")

        override fun exec(command: String, timeoutMs: Long): ShellResult {
            executedCommands.add(command)
            val trimmed = command.trim()
            if (trimmed.startsWith("settings get ")) {
                val parts = trimmed.substring("settings get ".length).split(" ")
                val key = "${parts[0]}/${parts[1]}"
                val value = currentSettings[key] ?: "null"
                return ShellResult(ok = true, exitCode = 0, stdout = value, stderr = "")
            } else if (trimmed.startsWith("settings put ")) {
                val parts = trimmed.substring("settings put ".length).split(" ", limit = 3)
                val key = "${parts[0]}/${parts[1]}"
                val value = if (parts.size > 2) parts[2] else ""
                currentSettings[key] = value
                return ShellResult(ok = true, exitCode = 0, stdout = "", stderr = "")
            } else if (trimmed.startsWith("settings delete ")) {
                val parts = trimmed.substring("settings delete ".length).split(" ")
                val key = "${parts[0]}/${parts[1]}"
                currentSettings.remove(key)
                return ShellResult(ok = true, exitCode = 0, stdout = "", stderr = "")
            }
            return ShellResult(ok = true, exitCode = 0, stdout = "", stderr = "")
        }
    }

    @Test
    fun `Signal definitions cover critical anti-detection keys`() {
        val devKeys = Signal.DEVELOPER_OPTIONS.keys
        assertEquals(1, devKeys.size)
        assertEquals("global/development_settings_enabled", devKeys[0].id)
        assertEquals("0", devKeys[0].hiddenValue)

        val usbKeys = Signal.USB_DEBUGGING.keys
        assertEquals("global/adb_enabled", usbKeys[0].id)
        assertEquals("0", usbKeys[0].hiddenValue)

        val wifiKeys = Signal.WIRELESS_DEBUGGING.keys
        assertEquals(1, wifiKeys.size)
        assertEquals("global/adb_wifi_enabled", wifiKeys[0].id)
        assertEquals("0", wifiKeys[0].hiddenValue)
    }

    @Test
    fun `hideSignal saves original value and writes hidden value`() {
        val mockShell = MockShell()
        val store = MemoryHidingStore()
        val manager = DynamicHidingManager(store = store, shellProvider = { mockShell })

        val ok = manager.hideSignal(Signal.DEVELOPER_OPTIONS, mockShell)
        assertTrue(ok)

        // Verifikasi nilai asli disimpan di store
        assertEquals("1", store.getOriginal("global/development_settings_enabled"))

        // Verifikasi nilai baru yang tertulis di sistem adalah "0"
        assertEquals("0", mockShell.currentSettings["global/development_settings_enabled"])
    }

    @Test
    fun `restoreSignal puts back original value and clears store`() {
        val mockShell = MockShell()
        val store = MemoryHidingStore()
        val manager = DynamicHidingManager(store = store, shellProvider = { mockShell })

        // 1. Hide
        manager.hideSignal(Signal.DEVELOPER_OPTIONS, mockShell)
        assertEquals("0", mockShell.currentSettings["global/development_settings_enabled"])

        // 2. Restore
        val ok = manager.restoreSignal(Signal.DEVELOPER_OPTIONS, mockShell)
        assertTrue(ok)

        // Verifikasi nilai asli kembali ke "1"
        assertEquals("1", mockShell.currentSettings["global/development_settings_enabled"])

        // Verifikasi store dibersihkan
        assertNull(store.getOriginal("global/development_settings_enabled"))
    }

    @Test
    fun `reconcile manages hiding when entering and leaving protected packages`() {
        val mockShell = MockShell()
        val store = MemoryHidingStore()
        val manager = DynamicHidingManager(store = store, shellProvider = { mockShell })

        val protectedApps = setOf("com.bank.secure", "com.zhiliaoapp.musically")

        // 1. User membuka app perbankan -> sinyal disembunyikan
        // 1. User membuka app perbankan -> sinyal disembunyikan (3 keys: dev, usb, wifi)
        manager.reconcile("com.bank.secure", protectedApps, shell = mockShell)
        assertEquals("0", mockShell.currentSettings["global/development_settings_enabled"])
        assertEquals("0", mockShell.currentSettings["global/adb_enabled"])
        assertEquals("0", mockShell.currentSettings["global/adb_wifi_enabled"])
        assertEquals(3, store.getAllSavedKeys().size)

        // 2. User kembali ke Home launcher -> sinyal otomatis dipulihkan
        manager.reconcile("com.miui.home", protectedApps, shell = mockShell)
        assertEquals("1", mockShell.currentSettings["global/development_settings_enabled"])
        assertEquals("1", mockShell.currentSettings["global/adb_enabled"])
        assertEquals("1", mockShell.currentSettings["global/adb_wifi_enabled"])
        assertTrue(store.getAllSavedKeys().isEmpty())
    }
}
