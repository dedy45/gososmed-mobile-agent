package com.gososmed.agent.input

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import java.util.concurrent.atomic.AtomicReference

/**
 * Maestro-Parity Virtual Keyboard IME (InputMethodService).
 * Provides zero-latency, lossless Unicode and emoji text injection into any focused input field
 * via native InputConnection.commitText, matching Maestro's exact architecture.
 */
class GoSosmedInputMethodService : InputMethodService() {

    private var receiverRegistered = false

    private val inputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_STATUS -> {
                    val status = status()
                    resultCode = if (status.ready) RESULT_OK else RESULT_ERROR
                    resultData = status.message
                    Log.i(TAG, "IME status: active=${status.active} ready=${status.ready} msg=${status.message}")
                }
                ACTION_COMMIT -> {
                    val rawText = intent.getStringExtra(EXTRA_TEXT)
                    val base64Text = intent.getStringExtra(EXTRA_TEXT_BASE64)
                    val textToCommit = when {
                        !rawText.isNullOrEmpty() -> rawText
                        !base64Text.isNullOrEmpty() -> {
                            try {
                                String(Base64.decode(base64Text, Base64.DEFAULT), Charsets.UTF_8)
                            } catch (_: Exception) { null }
                        }
                        else -> null
                    }

                    if (textToCommit == null) {
                        resultCode = RESULT_ERROR
                        resultData = "Missing text payload"
                        return
                    }

                    val res = commitTextInternal(textToCommit)
                    resultCode = if (res.success) RESULT_OK else RESULT_ERROR
                    resultData = res.message
                    Log.i(TAG, "IME commitText result=${res.success} msg=${res.message}")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeService.set(this)
        hasActiveInputConnection.set(false)
        registerImeReceiver()
        Log.i(TAG, "GoSosmed IME created")
    }

    override fun onCreateInputView(): View {
        return View(this)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        hasActiveInputConnection.set(currentInputConnection != null)
        Log.i(TAG, "onStartInput package=${attribute?.packageName} connected=${hasActiveInputConnection.get()}")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        hasActiveInputConnection.set(currentInputConnection != null)
    }

    override fun onFinishInput() {
        hasActiveInputConnection.set(false)
        super.onFinishInput()
    }

    override fun onDestroy() {
        activeService.compareAndSet(this, null)
        hasActiveInputConnection.set(false)
        unregisterImeReceiver()
        Log.i(TAG, "GoSosmed IME destroyed")
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private fun registerImeReceiver() {
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_COMMIT)
                addAction(ACTION_STATUS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(inputReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(inputReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun unregisterImeReceiver() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(inputReceiver)
            } catch (_: Exception) {}
            receiverRegistered = false
        }
    }

    fun commitTextInternal(text: String): CommitResult {
        return try {
            val ic = currentInputConnection
            if (ic == null) {
                CommitResult(success = false, message = "No active input connection")
            } else {
                ic.beginBatchEdit()
                val committed = ic.commitText(text, 1)
                ic.finishComposingText()
                ic.endBatchEdit()
                if (committed) {
                    CommitResult(success = true, message = "Committed ${text.length} chars")
                } else {
                    CommitResult(success = false, message = "commitText returned false")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to commit text via IME", t)
            CommitResult(success = false, message = t.message ?: "commitText_exception")
        }
    }

    companion object {
        const val IME_ID = "com.gososmed.agent/.input.GoSosmedInputMethodService"
        const val ACTION_COMMIT = "com.gososmed.agent.ime.commitText"
        const val ACTION_STATUS = "com.gososmed.agent.ime.status"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TEXT_BASE64 = "textBase64"
        private const val TAG = "GoSosmedIME"

        private const val RESULT_OK = 0
        private const val RESULT_ERROR = 1

        private val activeService = AtomicReference<GoSosmedInputMethodService?>()
        private val hasActiveInputConnection = AtomicReference(false)

        data class ImeStatus(val active: Boolean, val ready: Boolean, val message: String)
        data class CommitResult(val success: Boolean, val message: String)

        fun status(): ImeStatus {
            val service = activeService.get()
            val hasConnection = hasActiveInputConnection.get() && service?.currentInputConnection != null
            val message = when {
                service == null -> "GoSosmed IME is not active"
                !hasConnection -> "No active input connection"
                else -> "Ready"
            }
            return ImeStatus(active = service != null, ready = hasConnection, message = message)
        }

        fun commitText(text: String): CommitResult {
            val s = activeService.get() ?: return CommitResult(success = false, message = "GoSosmed IME is not active")
            return s.commitTextInternal(text)
        }
    }
}
