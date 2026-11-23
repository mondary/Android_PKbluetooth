package com.vibepop.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.vibepop.R
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.model.PopupConfig
import com.vibepop.ui.PopupActivity
import com.vibepop.util.PermissionHelper

/**
 * Overlay popup lifecycle manager (singleton).
 */
object PopupWindowManager {

    private const val TAG = "PopupWindowManager"

    private var windowManager: WindowManager? = null
    private var popupView: View? = null
    private var popupInteraction: PopupInteractionController? = null
    private var isShowing = false

    private var currentBinder: PopupViewBinder? = null
    private var currentDeviceAddress: String? = null
    private var budPanel: BudPanel? = null

    /**
     * Show the device popup.
     */
    @Synchronized
    fun showPopup(
        context: Context,
        deviceState: DeviceBatteryState = DeviceBatteryState.mock(),
        config: PopupConfig = PopupConfig()
    ) {
        // Dismiss any current popup first, including its lock-screen counterpart.
        dismissPopup(immediate = true)

        // 1. Check whether the display is locked or asleep.
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val isScreenOff = powerManager?.isInteractive == false
        val isLocked = keyguardManager?.isKeyguardLocked == true
        val overlayAllowed = PermissionHelper.hasOverlayPermission(context)

        // Overlay windows cannot appear above the lock screen, so use the
        // PopupActivity with showWhenLocked and turnScreenOn instead.
        if (isScreenOff || isLocked) {
            Log.d(TAG, "Device is locked ($isLocked) or screen is off ($isScreenOff), launching PopupActivity to penetrate lock screen")
            PopupActivity.start(context, deviceState, config)
            return
        }

        if (!overlayAllowed) {
            Log.w(TAG, "Cannot show popup: SYSTEM_ALERT_WINDOW permission missing")
            return
        }

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm
            if (BudPanel.matches(config.animationTheme)) {
                val wrap = WindowManager.LayoutParams.WRAP_CONTENT
                val fill = WindowManager.LayoutParams.MATCH_PARENT
                val params = WindowManager.LayoutParams(wrap, wrap,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT)
                val panel = BudPanel(context, config, deviceState, { full ->
                    params.width = if (full) fill else wrap
                    params.height = params.width
                    popupView?.let { if (it.isAttachedToWindow) wm.updateViewLayout(it, params) }
                }, { dismissPopup(immediate = true) })
                budPanel = panel
                val card = panel.card
                params.gravity = card.gravity
                params.y = card.offsetY
                if (card.startsFullScreen) { params.width = fill; params.height = fill }
                popupView = card.root
                wm.addView(card.root, params)
                currentDeviceAddress = deviceState.deviceAddress
                isShowing = true
                panel.start()
                if (config.isVibrationEnabled) triggerHapticFeedback(context)
                return
            }

            // 2. Inflate the popup with a MaterialComponents theme wrapper.
            val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_VibePop)
            val inflater = LayoutInflater.from(themedContext)
            val root = inflater.inflate(R.layout.layout_headset_popup, null)
            popupView = root

            // 3. Configure window flags, bottom gravity and transitions.
            val layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            @Suppress("DEPRECATION")
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                windowAnimations = R.style.PopupAnimation
                dimAmount = 0.25f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    blurBehindRadius = 80
                }
            }

            // 4. Compute the 40% screen-height popup size.
            val cardView = root.findViewById<View>(R.id.cardPopup)
            cardView.layoutParams = cardView.layoutParams.apply {
                height = PopupMetrics.targetPopupHeight(context)
            }

            // 5. Bind data and animation.
            val binder = PopupViewBinder(root, config) {
                dismissPopup()
            }
            currentBinder = binder
            currentDeviceAddress = deviceState.deviceAddress
            binder.bind(deviceState)

            // 6. Attach swipe-to-dismiss and the shared auto-dismiss controller.
            val interaction = PopupInteractionController(
                cardView = cardView,
                onDismiss = { dismissPopup(immediate = true) },
                dismissDelayMillis = config.resolveDismissDelayMillis()
            )
            popupInteraction = interaction
            interaction.attach()

            // 7. Attach to the window.
            wm.addView(root, params)
            isShowing = true

            // 8. Briefly wake the display so the popup is visible.
            if (isScreenOff) {
                wakeScreen(context)
            }

            // 9. Start the auto-dismiss timer.
            interaction.startAutoDismiss()

            // 10. Haptic feedback.
            if (config.isVibrationEnabled) {
                triggerHapticFeedback(context)
            }

            Log.d(TAG, "Popup window presented successfully for: ${deviceState.deviceName}")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to show popup window", e)
            dismissPopup(immediate = true)
        }
    }

    /**
     * Refresh only the battery badge when a reading arrives while the popup is showing.
     * Do not reload media or trigger another popup.
     */
    fun updateBatteryIfShowing(deviceAddress: String, batteryLevel: Int) {
        if (!isShowing) return
        if (deviceAddress.isBlank()) return
        val current = currentDeviceAddress ?: return
        if (!current.equals(deviceAddress, ignoreCase = true)) return
        try {
            currentBinder?.updateBattery(batteryLevel)
            budPanel?.battery(batteryLevel)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update battery on showing popup: ${e.message}")
        }
    }

    /**
     * Hide and remove the popup.
     */
    @Synchronized
    fun dismissPopup(immediate: Boolean = false) {
        // Overlay and lock-screen popups share a lifecycle; close both together.
        PopupActivity.dismissIfShowing()
        if (!immediate) budPanel?.let { it.dismiss(); return }
        budPanel?.dispose()
        budPanel = null

        popupInteraction?.cancel()
        popupInteraction = null

        if (!isShowing && popupView == null) return

        try {
            currentBinder?.release()
            currentBinder = null
            currentDeviceAddress = null

            popupView?.let { view ->
                if (view.isAttachedToWindow) {
                    if (immediate) {
                        windowManager?.removeViewImmediate(view)
                    } else {
                        windowManager?.removeView(view)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while dismissing popup", e)
        } finally {
            popupView = null
            windowManager = null
            isShowing = false
        }
    }

    /**
     * Briefly wake the display before showing over the lock screen.
     */
    private fun wakeScreen(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            @Suppress("DEPRECATION")
            powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "VibePop:OverlayWakeLock"
            )?.acquire(3000)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to wake screen: ${e.message}")
        }
    }

    /**
     * Light haptic feedback.
     */
    private fun triggerHapticFeedback(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Haptic feedback unavailable: ${e.message}")
        }
    }
}
