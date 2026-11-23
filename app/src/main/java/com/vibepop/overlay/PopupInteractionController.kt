package com.vibepop.overlay

import android.view.View

/**
 * Shared swipe-to-dismiss and auto-dismiss controller for overlay and lock-screen popups.
 */
class PopupInteractionController(
    private val cardView: View,
    private val onDismiss: () -> Unit,
    dismissDelayMillis: Long
) {

    private val autoDismissManager = PopupAutoDismissManager(dismissDelayMillis) { onDismiss() }

    private val touchListener = SlideDismissTouchListener(
        targetView = cardView,
        onDismiss = { onDismiss() },
        onTouchStateChanged = { isInteracting ->
            if (isInteracting) {
                autoDismissManager.pause()
            } else {
                autoDismissManager.resume()
            }
        }
    )

    /**
     * Bind the swipe-down gesture listener.
     */
    fun attach() {
        cardView.setOnTouchListener(touchListener)
    }

    /**
     * Start the auto-dismiss timer after the popup is attached.
     */
    fun startAutoDismiss() {
        autoDismissManager.start()
    }

    /**
     * Cancel the timer when the popup closes.
     */
    fun cancel() {
        autoDismissManager.cancel()
    }
}
