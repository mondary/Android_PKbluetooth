package com.vibepop.data.model

/**
 * Popup configuration preferences.
 */
data class PopupConfig(
    val autoDismissSeconds: Int = 4,
    val customDeviceName: String = "",
    val isServiceEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true,
    val isForceSpeakerphone: Boolean = true,        // Route audio to the phone speaker.
    val animationTheme: String = "classic_airpods", // "classic_airpods", "great_victory", "custom_media"
    val customMediaPath: String? = null,           // Imported video, GIF or image path.
    val customMediaType: String = "preset",        // "video", "image", "lottie", "preset"
    val videoDismissMode: String = "on_complete",  // Close on completion or after the timer.
    val targetDeviceAddresses: Set<String> = emptySet(), // Enabled device MAC addresses.
    val cropLeft: Float = 0f,                      // Normalized black-border crop (0..1).
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f
) : java.io.Serializable {

    /**
     * Whether the popup closes after video playback.
     */
    fun isVideoDismissOnComplete(): Boolean {
        val isVideoTheme = animationTheme == "great_victory" ||
                customMediaType == "video" ||
                customMediaPath?.lowercase()?.let {
                    it.endsWith(".mp4") || it.endsWith(".webm")
                } == true
        return isVideoTheme && videoDismissMode == "on_complete"
    }

    /**
     * Use a 60-second fallback for video completion; otherwise use the configured timer (minimum 2 seconds).
     */
    fun resolveDismissDelayMillis(): Long {
        return if (isVideoDismissOnComplete()) {
            60_000L
        } else {
            (autoDismissSeconds * 1000L).coerceAtLeast(2000L)
        }
    }
}
