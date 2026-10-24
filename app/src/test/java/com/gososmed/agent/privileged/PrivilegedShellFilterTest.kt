package com.gososmed.agent.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Uji firewall keamanan PrivilegedShell berbasis model Denylist / Blacklist.
 * Memastikan biner destruktif diblokir keras, dan biner diagnostik/otomasi agent diizinkan.
 */
class PrivilegedShellFilterTest {

    @Test
    fun `destructive binaries are blocked`() {
        val destructiveCommands = listOf(
            "reboot",
            "reboot bootloader",
            "/system/bin/reboot",
            "poweroff",
            "halt",
            "dd if=/dev/zero of=/dev/block/bootdevice",
            "fdisk /dev/block/sda",
            "mkfs.ext4 /dev/block/sda1",
            "mke2fs -t ext4 /dev/block/sda1",
            "wipe data",
            "fastboot flash boot boot.img",
            "remount",
            "disable-verity",
            "insmod evil.ko",
            "rmmod good.ko",
            "su",
            "su -c id",
            "magisk --daemon",
            "run-as com.other.app"
        )

        for (cmd in destructiveCommands) {
            val res = PrivilegedShell.validateCommand(cmd)
            assertNotNull("Perintah destruktif '$cmd' harus diblokir", res)
            assertTrue("Pesan failure harus diawali blocked", res!!.failure!!.startsWith("blocked:"))
        }
    }

    @Test
    fun `destructive command patterns are blocked`() {
        val dangerousPatterns = listOf(
            "setprop sys.powerctl reboot",
            "setprop sys.powerctl reboot,recovery",
            "rm -rf /",
            "rm -r /data",
            "rm -rf /sdcard",
            "rm -rf /system"
        )

        for (cmd in dangerousPatterns) {
            val res = PrivilegedShell.validateCommand(cmd)
            assertNotNull("Pola destruktif '$cmd' harus diblokir", res)
            assertTrue("Pesan failure harus diawali blocked", res!!.failure!!.startsWith("blocked:"))
        }
    }

    @Test
    fun `safe automation and diagnostic binaries are permitted`() {
        val safeCommands = listOf(
            "getprop ro.product.model",
            "getprop ro.build.version.release",
            "logcat -d -t 50",
            "logcat -d -s GoAgent:V",
            "ps -A",
            "top -n 1 -m 5",
            "service list",
            "service check clipboard",
            "uiautomator dump /sdcard/ui.xml",
            "screencap -p /sdcard/screen.png",
            "ls -la /sdcard/DCIM/Camera",
            "cat /proc/meminfo",
            "grep MemTotal /proc/meminfo",
            "mkdir -p /sdcard/Movies/test",
            "cp /sdcard/a.txt /sdcard/b.txt",
            "mv /sdcard/a.txt /sdcard/b.txt",
            "touch /sdcard/touch.tmp",
            "sleep 1",
            "echo hello",
            "which pm",
            "id",
            "date",
            "uptime",
            "ip addr show",
            "pm list packages -3",
            "pm path com.whatsapp",
            "am start -n com.gososmed.agent/.MainActivity",
            "am force-stop com.gososmed.agent",
            "input tap 500 500",
            "input swipe 100 200 100 800 300",
            "dumpsys window windows",
            "dumpsys battery",
            "settings get system screen_brightness",
            "settings put system screen_brightness 1",
            "cmd clipboard set-text 'test'",
            "rm /sdcard/DCIM/Camera/staged_123.jpg"
        )

        for (cmd in safeCommands) {
            val res = PrivilegedShell.validateCommand(cmd)
            assertNull("Perintah aman '$cmd' tidak boleh diblokir (res: ${res?.failure})", res)
        }
    }

    @Test
    fun `empty command returns command_empty`() {
        val res = PrivilegedShell.validateCommand("   ")
        assertNotNull(res)
        assertEquals("command_empty", res!!.failure)
    }
}
