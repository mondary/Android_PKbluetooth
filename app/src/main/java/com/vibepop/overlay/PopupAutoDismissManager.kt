package com.vibepop.overlay

import android.os.Handler
import android.os.Looper

/**
 * Schedules automatic popup dismissal.
 */
class PopupAutoDismissManager(
    private val dismissDelayMillis: Long,
    private val onDismissAction: () -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    private var isScheduled = false

    fun start() {
        cancel()
        dismissRunnable = Runnable {
            isScheduled = false
            onDismissAction()
        }
        handler.postDelayed(dismissRunnable!!, dismissDelayMillis)
        isScheduled = true
    }

    fun pause() {
        if (dismissRunnable != null) {
            handler.removeCallbacks(dismissRunnable!!)
            isScheduled = false
        }
    }

    fun resume() {
        start()
    }

    fun cancel() {
        dismissRunnable?.let {
            handler.removeCallbacks(it)
        }
        dismissRunnable = null
        isScheduled = false
    }
}
