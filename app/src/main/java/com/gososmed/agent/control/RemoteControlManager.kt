package com.gososmed.agent.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.gososmed.agent.AgentAccessibilityService
import com.gososmed.agent.privileged.PrivilegedShellHolder

/**
 * High-performance remote control and touch gesture dispatcher.
 * Prioritizes privileged shell (UID 2000 INJECT_EVENTS) for 60 FPS zero-throttle latency,
 * falling back to AccessibilityService.dispatchGesture and continueStroke.
 */
object RemoteControlManager {
    private const val TAG = "RemoteControl"

    private var activeStroke: GestureDescription.StrokeDescription? = null
    private var isTracking = false
    private var lastX = 0f
    private var lastY = 0f

    fun handleCommand(cmd: String): Boolean {
        val svc = AgentAccessibilityService.instance
        val shell = PrivilegedShellHolder.get()
        if (cmd.isBlank()) return false
        val parts = cmd.split(":", limit = 2)
        val rawOp = parts[0].trim().lowercase()
        val op = rawOp.removePrefix("button_")
        val arg = if (parts.size > 1) parts[1].trim() else ""

        // Auto-Wake: Bangunkan layar secara otomatis jika perangkat sedang tidur/layar padam.
        // Android menolak injeksi gesture (A11y/Shell) saat layar dalam kondisi non-interaktif/mati.
        val targetCtx = svc ?: AgentAccessibilityService.getAppContext()
        val pm = targetCtx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive && op !in listOf("power", "lock")) {
            Log.i(TAG, "Layar padam saat perintah $op diterima — memicu bangunkan layar")
            if (svc != null) {
                svc.wakeScreen()
            } else if (shell.status().available) {
                shell.exec("input keyevent 224")
            }
            com.gososmed.agent.capture.CaptureService.instance?.requestKeyFrame()
        }

        return when (op) {
            "d", "down" -> { // Touch Down
                if (svc == null && !shell.status().available) return false
                val p = parseCoords(arg, svc)
                isTracking = true
                lastX = p.first
                lastY = p.second
                if (shell.status().available) {
                    val res = shell.exec("input motionevent DOWN ${p.first.toInt()} ${p.second.toInt()}")
                    res.failure == null && res.ok
                } else {
                    true
                }
            }

            "m", "move" -> { // Drag / Pointer Move
                if ((svc == null && !shell.status().available) || !isTracking) return false
                val p = parseCoords(arg, svc)
                lastX = p.first
                lastY = p.second
                if (shell.status().available) {
                    val res = shell.exec("input motionevent MOVE ${p.first.toInt()} ${p.second.toInt()}")
                    res.failure == null && res.ok
                } else {
                    true
                }
            }

            "u", "up" -> { // Touch Up
                if ((svc == null && !shell.status().available) || !isTracking) return false
                val p = parseCoords(arg, svc)
                isTracking = false
                if (shell.status().available) {
                    val res = shell.exec("input motionevent UP ${p.first.toInt()} ${p.second.toInt()}")
                    res.failure == null && res.ok
                } else {
                    true
                }
            }

            "t", "tap" -> {
                if (svc == null && !shell.status().available) return false
                val p = parseCoords(arg, svc)
                val x = p.first.toInt()
                val y = p.second.toInt()
                var handled = false
                if (shell.status().available) {
                    val res = shell.exec("input tap $x $y")
                    if (res.failure == null && res.ok) {
                        handled = true
                    } else {
                        Log.w(TAG, "shell input tap gagal (${res.failure ?: res.stderr}); fallback ke gesture")
                    }
                }
                if (!handled && svc != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        svc.tap(x, y)
                    }
                    handled = true
                }
                handled
            }

            "l", "longpress" -> {
                if (svc == null && !shell.status().available) return false
                val p = parseCoords(arg, svc)
                val x = p.first.toInt(); val y = p.second.toInt()
                var handled = false
                if (shell.status().available) {
                    val res = shell.exec("input swipe $x $y $x $y 700")
                    if (res.failure == null && res.ok) {
                        handled = true
                    } else {
                        Log.w(TAG, "shell input longpress gagal (${res.failure ?: res.stderr}); fallback ke gesture")
                    }
                }
                if (!handled && svc != null) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        svc.swipe(x, y, x, y, 700L)
                    }
                    handled = true
                }
                handled
            }

            "s", "swipe" -> {
                if (svc == null && !shell.status().available) return false
                val nums = arg.split(',').mapNotNull { it.trim().toIntOrNull() }
                if (nums.size >= 4) {
                    val (rw, rh) = getRealDisplaySize(svc ?: AgentAccessibilityService.getAppContext())
                    val x1 = if (nums[0] in 0..10000) (nums[0] * (rw - 1)) / 10000 else nums[0]
                    val y1 = if (nums[1] in 0..10000) (nums[1] * (rh - 1)) / 10000 else nums[1]
                    val x2 = if (nums[2] in 0..10000) (nums[2] * (rw - 1)) / 10000 else nums[2]
                    val y2 = if (nums[3] in 0..10000) (nums[3] * (rh - 1)) / 10000 else nums[3]
                    val dur = nums.getOrElse(4) { 200 }
                    var handled = false
                    if (shell.status().available) {
                        val res = shell.exec("input swipe $x1 $y1 $x2 $y2 $dur")
                        if (res.failure == null && res.ok) {
                            handled = true
                        } else {
                            Log.w(TAG, "shell input swipe gagal (${res.failure ?: res.stderr}); fallback ke gesture")
                        }
                    }
                    if (!handled && svc != null) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            svc.swipe(x1, y1, x2, y2, dur.toLong())
                        }
                        handled = true
                    }
                    handled
                } else {
                    false
                }
            }

            "a" -> {
                when (arg) {
                    "back" -> svc?.pressBack() ?: false
                    "home" -> svc?.pressHome() ?: false
                    "recents" -> svc?.openRecents() ?: false
                    "power", "lock" -> if (svc != null || shell.status().available) { togglePower(svc); true } else false
                    else -> false
                }
            }

            "back" -> svc?.pressBack() ?: false
            "home" -> svc?.pressHome() ?: false
            "recents" -> svc?.openRecents() ?: false
            "wake" -> if (svc != null) { svc.wakeScreen() } else if (shell.status().available) { shell.exec("input keyevent 224").ok } else false
            "power", "lock" -> if (svc != null || shell.status().available) { togglePower(svc); true } else false
            "volume_up", "volup" -> if (svc != null || shell.status().available) { adjustVolume(svc, raise = true); true } else false
            "volume_down", "voldown" -> if (svc != null || shell.status().available) { adjustVolume(svc, raise = false); true } else false
            "rotate" -> if (svc != null || shell.status().available) { toggleRotation(svc); true } else false
            "key", "keyevent" -> {
                val keyCode = arg.toIntOrNull() ?: return false
                val shell = PrivilegedShellHolder.get()
                if (shell.status().available) {
                    shell.exec("input keyevent $keyCode")
                    true
                } else if (svc != null) {
                    when (keyCode) {
                        4 -> svc.pressBack()
                        3 -> svc.pressHome()
                        187 -> svc.openRecents()
                        24 -> { adjustVolume(svc, raise = true); true }
                        25 -> { adjustVolume(svc, raise = false); true }
                        26 -> { togglePower(svc); true }
                        else -> false
                    }
                } else {
                    false
                }
            }
            "text" -> {
                if (svc == null) return false
                val shell = PrivilegedShellHolder.get()
                if (shell.status().available) {
                    val escaped = arg.replace(" ", "%s").replace("'", "\\'")
                    val res = shell.exec("input text '$escaped'")
                    if (res.ok) return true
                }
                svc.setText(arg)
            }
            "clipboard" -> {
                val shell = PrivilegedShellHolder.get()
                if (shell.status().available) {
                    val escaped = arg.replace("'", "\\'")
                    shell.exec("cmd clipboard set-text '$escaped'")
                    true
                } else false
            }
            "maestro_type", "type_unicode", "paste_text" -> {
                val text = arg
                val imeResult = com.gososmed.agent.input.GoSosmedInputMethodService.commitText(text)
                if (imeResult.success) {
                    true
                } else if (svc != null) {
                    val ok = com.gososmed.agent.NodeActionExecutor.setClipboard(svc.applicationContext, text)
                    if (ok) {
                        com.gososmed.agent.NodeActionExecutor.pasteText(svc, null)
                    } else {
                        svc.setText(text)
                    }
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun togglePower(svc: AccessibilityService?) {
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            shell.exec("input keyevent 26")
            return
        }
        val targetCtx = svc ?: AgentAccessibilityService.getAppContext()
        val pm = targetCtx?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && pm.isInteractive) {
            if (svc != null) {
                if (Build.VERSION.SDK_INT >= 28) {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
                }
            }
        } else if (pm != null) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "goso:wake").acquire(1000)
        }
    }

    private fun adjustVolume(svc: AccessibilityService?, raise: Boolean) {
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            shell.exec("input keyevent ${if (raise) 24 else 25}")
            return
        }
        val targetCtx = svc ?: AgentAccessibilityService.getAppContext()
        val am = targetCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val dir = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI)
    }

    private fun toggleRotation(svc: AccessibilityService?) {
        val shell = PrivilegedShellHolder.get()
        if (shell.status().available) {
            val userRot = shell.exec("settings get system user_rotation").stdout.trim()
            val nextRot = if (userRot == "1") "0" else "1"
            shell.exec("settings put system accelerometer_rotation 0")
            shell.exec("settings put system user_rotation $nextRot")
        }
    }

    /**
     * Resolves normalized 0..10000 coordinates to real physical pixels.
     * MUST match CaptureService.reconfigure() which encodes using
     * wm.maximumWindowMetrics.bounds (true physical display, not inset displayMetrics).
     * Using displayMetrics.heightPixels would be ~246px shorter on devices with
     * navigation bars, causing all taps to land too high.
     */
    fun parseCoords(str: String, svc: AccessibilityService? = null): Pair<Float, Float> {
        val parts = str.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size < 2) return 0f to 0f
        val rawX = parts[0]
        val rawY = parts[1]

        // Get true physical display dimensions (same as CaptureService encoder)
        val ctx = svc ?: AgentAccessibilityService.getAppContext()
        val (realW, realH) = getRealDisplaySize(ctx)

        val px = if (rawX in 0..10000) {
            (rawX * (realW - 1)).toFloat() / 10000f
        } else {
            rawX.toFloat()
        }

        val py = if (rawY in 0..10000) {
            (rawY * (realH - 1)).toFloat() / 10000f
        } else {
            rawY.toFloat()
        }

        return px to py
    }

    /** Physical display size matching CaptureService.reconfigure() */
    private fun getRealDisplaySize(ctx: Context?): Pair<Int, Int> {
        if (ctx == null) return 1080 to 2400
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val bounds = wm.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else if (wm != null) {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        } else {
            val dm = ctx.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }
}
