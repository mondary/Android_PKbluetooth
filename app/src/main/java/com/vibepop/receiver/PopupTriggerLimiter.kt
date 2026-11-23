package com.vibepop.receiver

/**
 * Debounce repeated popup triggers during Bluetooth handshakes.
 */
object PopupTriggerLimiter {

    private var lastTriggerTime = 0L
    private const val DEFAULT_DEBOUNCE_INTERVAL = 3_000L // Three-second debounce.

    @Synchronized
    fun canTrigger(intervalMillis: Long = DEFAULT_DEBOUNCE_INTERVAL): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime > intervalMillis) {
            lastTriggerTime = now
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        lastTriggerTime = 0L
    }
}
