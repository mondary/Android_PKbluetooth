package com.vibepop.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.vibepop.R
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.model.PopupConfig
import com.vibepop.databinding.ActivityPopupBinding
import com.vibepop.overlay.PopupInteractionController
import com.vibepop.overlay.PopupMetrics
import com.vibepop.overlay.PopupViewBinder

/**
 * Popup activity for the lock screen, a sleeping display, or missing overlay permission.
 */
class PopupActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_DEVICE_STATE = "extra_device_state"
        private const val EXTRA_POPUP_CONFIG = "extra_popup_config"

        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var currentInstance: PopupActivity? = null

        /**
         * Whether the lock-screen popup is showing.
         */
        val isActivityRunning: Boolean
            get() = currentInstance != null

        fun start(
            context: Context,
            deviceState: DeviceBatteryState = DeviceBatteryState.mock(),
            config: PopupConfig = PopupConfig()
        ) {
            try {
                // 1. Wake the display.
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                @Suppress("DEPRECATION")
                val wakeLock = pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                    "VibePop:LockScreenWakeLock"
                )
                wakeLock?.acquire(3000)

                // 2. Start the lock-screen popup activity.
                val intent = Intent(context, PopupActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_DEVICE_STATE, deviceState)
                    putExtra(EXTRA_POPUP_CONFIG, config)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        /**
         * Dismiss the lock-screen popup when the device disconnects or a new popup replaces it.
         */
        fun dismissIfShowing() {
            currentInstance?.let { instance ->
                mainHandler.post { instance.dismiss() }
            }
        }

        /**
         * Refresh the battery badge while the lock-screen popup is showing.
         */
        fun updateBatteryIfShowing(deviceAddress: String, batteryLevel: Int) {
            currentInstance?.let { instance ->
                mainHandler.post { instance.updateBattery(deviceAddress, batteryLevel) }
            }
        }
    }

    private lateinit var binding: ActivityPopupBinding
    private var binder: PopupViewBinder? = null
    private var interaction: PopupInteractionController? = null
    private var currentDeviceAddress: String? = null
    private var panel: com.vibepop.overlay.BudPanel? = null

    private fun showPanel(config: PopupConfig, state: DeviceBatteryState): Boolean {
        val host = binding.popupActivityRoot
        panel?.let { it.dispose(); host.removeView(it.card.root) }
        panel = null
        binding.layoutPopup.root.visibility = android.view.View.VISIBLE
        if (!com.vibepop.overlay.BudPanel.matches(config.animationTheme)) return false
        interaction?.cancel()
        binder?.release()
        binder = null
        binding.layoutPopup.root.visibility = android.view.View.GONE
        val p = com.vibepop.overlay.BudPanel(this, config, state, { full ->
            panel?.card?.root?.let { v -> v.layoutParams = v.layoutParams.apply {
                width = if (full) -1 else -2; height = width
            } }
        }, { dismiss() })
        panel = p
        val card = p.card
        val size = if (card.startsFullScreen) -1 else -2
        host.addView(card.root, android.widget.FrameLayout.LayoutParams(size, size, card.gravity))
        host.setOnClickListener { p.dismiss() }
        p.start()
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentInstance = this

        // 1. Show over the lock screen and wake the display.
        setupLockScreenFlags()

        binding = ActivityPopupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 2. Read the device state and configuration.
        val deviceState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_DEVICE_STATE, DeviceBatteryState::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_DEVICE_STATE) as? DeviceBatteryState
        } ?: DeviceBatteryState.mock()

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_POPUP_CONFIG, PopupConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_POPUP_CONFIG) as? PopupConfig
        } ?: PopupConfig()
        currentDeviceAddress = deviceState.deviceAddress
        if (showPanel(config, deviceState)) return

        // 3. Use 40% of the screen height.
        val cardView = binding.layoutPopup.cardPopup
        cardView.layoutParams = cardView.layoutParams.apply {
            height = PopupMetrics.targetPopupHeight(this@PopupActivity)
        }

        // 4. Bind media and battery state.
        binder = PopupViewBinder(binding.layoutPopup.root, config) {
            dismiss()
        }
        binder?.bind(deviceState)

        // 5. Attach swipe-to-dismiss and the shared auto-dismiss controller.
        interaction = PopupInteractionController(
            cardView = cardView,
            onDismiss = { dismiss() },
            dismissDelayMillis = config.resolveDismissDelayMillis()
        )
        interaction?.attach()
        interaction?.startAutoDismiss()

        // 6. Close when the translucent backdrop is tapped.
        binding.popupActivityRoot.setOnClickListener {
            dismiss()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val deviceState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_DEVICE_STATE, DeviceBatteryState::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_DEVICE_STATE) as? DeviceBatteryState
        } ?: DeviceBatteryState.mock()

        val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_POPUP_CONFIG, PopupConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_POPUP_CONFIG) as? PopupConfig
        } ?: PopupConfig()
        currentDeviceAddress = deviceState.deviceAddress
        if (showPanel(config, deviceState)) return
        binding.popupActivityRoot.setOnClickListener { dismiss() }

        val cardView = binding.layoutPopup.cardPopup
        cardView.layoutParams = cardView.layoutParams.apply {
            height = PopupMetrics.targetPopupHeight(this@PopupActivity)
        }

        binder?.release()
        binder = PopupViewBinder(binding.layoutPopup.root, config) {
            dismiss()
        }
        binder?.bind(deviceState)

        interaction?.cancel()
        interaction = PopupInteractionController(
            cardView = cardView,
            onDismiss = { dismiss() },
            dismissDelayMillis = config.resolveDismissDelayMillis()
        )
        interaction?.attach()
        interaction?.startAutoDismiss()
    }

    /**
     * Refresh the badge only for the device currently shown.
     */
    private fun updateBattery(deviceAddress: String, batteryLevel: Int) {
        val current = currentDeviceAddress ?: return
        if (current.isBlank() || !current.equals(deviceAddress, ignoreCase = true)) return
        binder?.updateBattery(batteryLevel)
        panel?.battery(batteryLevel)
    }

    private fun setupLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun dismiss() {
        if (isFinishing || isDestroyed) return
        interaction?.cancel()
        interaction = null
        binder?.release()
        binder = null
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, R.anim.slide_down_out)
    }

    override fun onDestroy() {
        panel?.dispose()
        super.onDestroy()
        if (currentInstance === this) {
            currentInstance = null
        }
        interaction?.cancel()
        binder?.release()
    }
}
