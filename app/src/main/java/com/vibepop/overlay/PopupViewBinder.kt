@file:Suppress("DEPRECATION")

package com.vibepop.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.vibepop.R
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.model.PopupConfig
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * Binds popup data, loads media (MP4, GIF, image, Lottie) and renders the battery badge.
 */
class PopupViewBinder(
    private val rootView: View,
    private val config: PopupConfig,
    private val onDismissRequested: () -> Unit
) {

    companion object {
        private const val TAG = "PopupViewBinder"

        /** Decode images and GIFs off the main thread. */
        private val imageDecodeExecutor = Executors.newSingleThreadExecutor()

        /** Sampled decode target; the popup does not need the original resolution. */
        private const val SAMPLE_TARGET_SIZE = 1600
    }

    private val tvDeviceName: TextView = rootView.findViewById(R.id.tvDeviceName)
    private val tvConnectionStatus: TextView = rootView.findViewById(R.id.tvConnectionStatus)
    private val lottieView: LottieAnimationView = rootView.findViewById(R.id.lottieAnimationView)
    private val videoView: FillVideoView = rootView.findViewById(R.id.videoView)
    private val ivCustomImage: ImageView = rootView.findViewById(R.id.ivCustomImage)
    private val viewGradientOverlay: View? = rootView.findViewById(R.id.viewGradientOverlay)

    private val tvBatteryLevel: TextView = rootView.findViewById(R.id.tvBatteryLevel)
    private val ivBatteryIcon: ImageView = rootView.findViewById(R.id.ivBatteryIcon)
    private val ivBatteryCharging: ImageView = rootView.findViewById(R.id.ivBatteryCharging)

    private val mainPostHandler = Handler(Looper.getMainLooper())

    /** Keep the last charging state when the battery level refreshes. */
    private var lastIsCharging = false

    @Volatile
    private var released = false

    fun bind(deviceState: DeviceBatteryState) {
        val context = rootView.context

        // Prefer the custom name; otherwise show the connected device name.
        val displayName = when {
            config.customDeviceName.isNotBlank() -> config.customDeviceName
            deviceState.deviceName.isNotBlank() -> deviceState.deviceName
            else -> context.getString(R.string.device_name_fallback)
        }
        tvDeviceName.text = displayName
        tvConnectionStatus.text = context.getString(R.string.status_connected_text)

        // Bind the reported battery level.
        lastIsCharging = deviceState.isCharging
        bindSingleBattery(deviceState.batteryLevel, deviceState.isCharging)

        // Select and load the configured media.
        setupMedia()
    }

    /**
     * Refresh only the battery badge when a reading arrives; keep the known charging state.
     */
    fun updateBattery(batteryLevel: Int) {
        bindSingleBattery(batteryLevel, lastIsCharging)
    }

    private fun setupMedia() {
        try {
            if (config.animationTheme == "bud_flight") {
                videoView.visibility = View.GONE
                ivCustomImage.visibility = View.GONE
                lottieView.visibility = View.GONE
                viewGradientOverlay?.visibility = View.VISIBLE
                rootView.findViewById<View>(R.id.budFlightView).visibility = View.VISIBLE
                return
            }
            // 1. Built-in "great_victory" video preset.
            if (config.animationTheme == "great_victory") {
                viewGradientOverlay?.visibility = View.GONE
                playPresetVideo(R.raw.video_great_victory)
                return
            }

            // 2. Imported local media (video, image or Lottie).
            if (!config.customMediaPath.isNullOrBlank()) {
                val file = File(config.customMediaPath)
                if (file.exists()) {
                    when (config.customMediaType) {
                        "video" -> {
                            viewGradientOverlay?.visibility = View.GONE
                            playVideo(file)
                            return
                        }
                        "image" -> {
                            viewGradientOverlay?.visibility = View.GONE
                            showImage(file)
                            return
                        }
                        "lottie" -> {
                            viewGradientOverlay?.visibility = View.VISIBLE
                            playLottieFile(file)
                            return
                        }
                    }
                }
            }

            // 3. Classic AirPods Lottie animation.
            viewGradientOverlay?.visibility = View.VISIBLE
            videoView.visibility = View.GONE
            ivCustomImage.visibility = View.GONE
            lottieView.visibility = View.VISIBLE

            lottieView.repeatCount = LottieDrawable.INFINITE
            lottieView.setAnimation("headset_animation.json")
            lottieView.playAnimation()

        } catch (e: Exception) {
            showFallback()
        }
    }

    private fun playPresetVideo(rawResId: Int) {
        lottieView.visibility = View.GONE
        ivCustomImage.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        videoView.setCropBounds(config.cropLeft, config.cropTop, config.cropRight, config.cropBottom)
        val videoUri = Uri.parse("android.resource://${rootView.context.packageName}/$rawResId")
        videoView.setVideoURI(videoUri)
        bindVideoListeners()
        videoView.start()
    }

    private fun playVideo(file: File) {
        lottieView.visibility = View.GONE
        ivCustomImage.visibility = View.GONE
        videoView.visibility = View.VISIBLE

        videoView.setCropBounds(config.cropLeft, config.cropTop, config.cropRight, config.cropBottom)
        videoView.setVideoPath(file.absolutePath)
        bindVideoListeners()
        videoView.start()
    }

    /**
     * Bind video callbacks: looping, volume, speaker routing, completion and error fallback.
     */
    private fun bindVideoListeners() {
        val isOnComplete = config.videoDismissMode == "on_complete"

        videoView.onVideoPreparedExtra = prepared@{ mp ->
            if (released) return@prepared
            try {
                mp.isLooping = !isOnComplete // Loop only in timer mode.
                mp.setVolume(1.0f, 1.0f)     // Play the video soundtrack.
                if (config.isForceSpeakerphone) {
                    routeAudioToSpeaker(mp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure prepared video: ${e.message}")
            }
            mp.start()
        }

        if (isOnComplete) {
            videoView.setOnCompletionListener {
                if (!released) onDismissRequested()
            }
        } else {
            videoView.setOnCompletionListener(null)
        }

        videoView.setOnErrorListener { _, _, _ ->
            if (!released) {
                mainPostHandler.post {
                    if (!released) showFallback()
                }
            }
            true
        }
    }

    private fun routeAudioToSpeaker(mp: MediaPlayer) {
        try {
            val context = rootView.context
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return

            val speakerDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speakerDevice != null) {
                mp.setPreferredDevice(speakerDevice)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speakerDevice = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun release() {
        released = true
        try {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
        } catch (e: Exception) {
            // ignore
        }

        // Restore the default audio route.
        try {
            val context = rootView.context
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager?.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = false
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Decode GIFs with ImageDecoder or Movie, and sampled still images with EXIF rotation.
     */
    private fun showImage(file: File) {
        lottieView.visibility = View.GONE
        videoView.visibility = View.GONE
        ivCustomImage.visibility = View.VISIBLE
        ivCustomImage.setImageDrawable(null)

        imageDecodeExecutor.execute {
            if (released || !file.exists()) return@execute
            val bytes = try {
                file.readBytes()
            } catch (e: Exception) {
                null
            }
            if (released || bytes == null) return@execute

            val isGif = bytes.size >= 6 &&
                    bytes[0] == 'G'.code.toByte() &&
                    bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() &&
                    bytes[3] == '8'.code.toByte()

            val drawable = if (isGif) {
                decodeGifDrawable(bytes)
            } else {
                decodeSampledBitmap(file)?.let { BitmapDrawable(rootView.resources, it) }
            }

            if (released) return@execute
            mainPostHandler.post {
                if (released) return@post
                if (drawable != null) {
                    ivCustomImage.setImageDrawable(drawable)
                    if (drawable is AnimatedImageDrawable) {
                        try {
                            drawable.start()
                        } catch (e: Exception) {
                            // Ignore if already started or not attached.
                        }
                    }
                } else {
                    showFallback()
                }
            }
        }
    }

    /**
     * Decode GIFs with ImageDecoder on API 28+, falling back to Movie on API 26-27.
     */
    private fun decodeGifDrawable(bytes: ByteArray): Drawable? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val source = ImageDecoder.createSource(bytes)
                ImageDecoder.decodeDrawable(source)
            } catch (e: Exception) {
                Movie.decodeByteArray(bytes, 0, bytes.size)?.let { GifMovieDrawable(it) }
            }
        } else {
            Movie.decodeByteArray(bytes, 0, bytes.size)?.let { GifMovieDrawable(it) }
        }
    }

    /**
     * Sample and decode a still image off the main thread, applying EXIF rotation.
     */
    private fun decodeSampledBitmap(file: File): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) return null

            var sampleSize = 1
            var width = boundsOptions.outWidth
            var height = boundsOptions.outHeight
            while (width / 2 >= SAMPLE_TARGET_SIZE || height / 2 >= SAMPLE_TARGET_SIZE) {
                width /= 2
                height /= 2
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            applyExifRotation(file, bitmap)
        } catch (e: Exception) {
            null
        }
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val rotation = try {
            when (ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (rotation == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun playLottieFile(file: File) {
        videoView.visibility = View.GONE
        ivCustomImage.visibility = View.GONE
        lottieView.visibility = View.VISIBLE

        lottieView.repeatCount = LottieDrawable.INFINITE
        lottieView.setAnimation(FileInputStream(file), null)
        lottieView.playAnimation()
    }

    private fun showFallback() {
        try {
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
        } catch (e: Exception) {
            // ignore
        }
        videoView.visibility = View.GONE
        ivCustomImage.visibility = View.GONE
        lottieView.visibility = View.VISIBLE
        try {
            lottieView.repeatCount = LottieDrawable.INFINITE
            lottieView.setAnimation("headset_animation.json")
            lottieView.playAnimation()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun bindSingleBattery(batteryPercent: Int, isCharging: Boolean) {
        val context = rootView.context
        if (batteryPercent < 0) {
            tvBatteryLevel.text = context.getString(R.string.battery_unknown)
            tvBatteryLevel.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            ivBatteryIcon.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
            ivBatteryCharging.visibility = View.GONE
            return
        }

        tvBatteryLevel.text = context.getString(R.string.battery_percent, batteryPercent)
        ivBatteryCharging.visibility = if (isCharging) View.VISIBLE else View.GONE

        // Color the badge by level and charging state.
        val colorRes = when {
            isCharging -> R.color.battery_charging_icon
            batteryPercent <= 20 -> R.color.battery_red
            batteryPercent <= 40 -> R.color.battery_yellow
            else -> R.color.battery_green
        }
        val resolvedColor = ContextCompat.getColor(context, colorRes)
        tvBatteryLevel.setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        ivBatteryIcon.setColorFilter(resolvedColor)
        if (isCharging) {
            ivBatteryCharging.setColorFilter(resolvedColor)
        }
    }
}
