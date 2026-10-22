package com.gososmed.agent.control

import org.junit.Assert.*
import org.junit.Test

class RemoteControlManagerTest {

    @Test
    fun testBlankOrUnknownCommandReturnsFalse() {
        assertFalse(RemoteControlManager.handleCommand(""))
        assertFalse(RemoteControlManager.handleCommand("   "))
        assertFalse(RemoteControlManager.handleCommand("invalid_op:123"))
    }

    @Test
    fun testCommandsFailGracefullyWhenServiceNull() {
        assertFalse(RemoteControlManager.handleCommand("d:500,500"))
        assertFalse(RemoteControlManager.handleCommand("m:500,600"))
        assertFalse(RemoteControlManager.handleCommand("u:500,600"))
        assertFalse(RemoteControlManager.handleCommand("tap:500,500"))
        assertFalse(RemoteControlManager.handleCommand("swipe:100,200,100,800,200"))
        assertFalse(RemoteControlManager.handleCommand("back"))
        assertFalse(RemoteControlManager.handleCommand("home"))
        assertFalse(RemoteControlManager.handleCommand("recents"))
        assertFalse(RemoteControlManager.handleCommand("wake"))
        assertFalse(RemoteControlManager.handleCommand("text:hello"))
        assertFalse(RemoteControlManager.handleCommand("power"))
        assertFalse(RemoteControlManager.handleCommand("volume_up"))
        assertFalse(RemoteControlManager.handleCommand("volume_down"))
        assertFalse(RemoteControlManager.handleCommand("rotate"))
        assertFalse(RemoteControlManager.handleCommand("button_power"))
        assertFalse(RemoteControlManager.handleCommand("button_volume_up"))
        assertFalse(RemoteControlManager.handleCommand("key:66"))
        assertFalse(RemoteControlManager.handleCommand("keyevent:67"))
        assertFalse(RemoteControlManager.handleCommand("t:500,500"))
        assertFalse(RemoteControlManager.handleCommand("l:500,500"))
        assertFalse(RemoteControlManager.handleCommand("s:100,200,100,800,200"))
        assertFalse(RemoteControlManager.handleCommand("a:back"))
        assertFalse(RemoteControlManager.handleCommand("a:home"))
        assertFalse(RemoteControlManager.handleCommand("clipboard:test"))
    }
}
