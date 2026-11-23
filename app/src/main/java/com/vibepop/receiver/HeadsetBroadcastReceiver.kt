package com.vibepop.receiver

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.vibepop.R
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.repository.PreferencesRepository
import com.vibepop.data.repository.BatteryHistory
import com.vibepop.overlay.PopupWindowManager
import com.vibepop.ui.PopupActivity

/**
 * Listens for Bluetooth connections and battery updates to trigger the popup.
 */
class HeadsetBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "HeadsetReceiver"
        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"
        private val historyExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!PreferencesRepository(context).getPopupConfig().isServiceEnabled) return
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

        val fallbackName = context.getString(R.string.device_name_fallback)
        val deviceName = try { device?.name ?: fallbackName } catch (e: Exception) { fallbackName }
        val deviceAddress = try { device?.address ?: "" } catch (e: Exception) { "" }
        Log.d(TAG, "Received broadcast action: $action, device: $deviceName ($deviceAddress)")

        when (action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                handleDeviceConnected(context, intent, device)
            }

            ACTION_BATTERY_LEVEL_CHANGED -> {
                handleBatteryLevelChanged(context, intent, device)
            }

            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                Log.d(TAG, "Profile state changed: $state")
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    handleDeviceConnected(context, intent, device)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    PopupWindowManager.dismissPopup()
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                PopupWindowManager.dismissPopup()
            }
        }
    }

    /**
     * Refresh the badge on battery updates without opening another popup.
     */
    private fun handleBatteryLevelChanged(context: Context, intent: Intent, device: BluetoothDevice?) {
        val deviceAddress = try { device?.address ?: "" } catch (e: Exception) { "" }
        if (deviceAddress.isBlank()) {
            Log.d(TAG, "Battery level changed but device address missing, ignoring")
            return
        }
        val level = getRealBatteryLevel(intent, device)
        val name = try { device?.name ?: context.getString(R.string.device_name_fallback) } catch (_: SecurityException) { context.getString(R.string.device_name_fallback) }
        if (level in 0..100) {
            val pending = goAsync()
            val appContext = context.applicationContext
            historyExecutor.execute {
                try {
                    BatteryHistory(appContext).use { it.record(deviceAddress, name, level) }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to record battery history", e)
                } finally {
                    pending.finish()
                }
            }
        }
        Log.d(TAG, "Battery level updated: $deviceAddress -> $level")
        PopupWindowManager.updateBatteryIfShowing(deviceAddress, level)
        PopupActivity.updateBatteryIfShowing(deviceAddress, level)
    }

    private fun handleDeviceConnected(context: Context, intent: Intent, device: BluetoothDevice?) {
        // Debounce repeated triggers.
        if (!PopupTriggerLimiter.canTrigger()) {
            Log.d(TAG, "Debounce limit reached, skipping popup trigger")
            return
        }

        val prefsRepo = PreferencesRepository(context)
        val globalConfig = prefsRepo.getPopupConfig()

        // Respect the monitoring switch; static receivers may still get ACL broadcasts.
        if (!globalConfig.isServiceEnabled) {
            Log.d(TAG, "Monitor service disabled, skipping popup trigger")
            return
        }

        var rawDeviceName = context.getString(R.string.default_device_name)
        var deviceAddress = ""

        try {
            if (device != null) {
                deviceAddress = device.address ?: ""
                val rawName = device.name
                if (!rawName.isNullOrBlank()) {
                    rawDeviceName = rawName
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException reading device info (permission missing): ${e.message}")
        }

        Log.d(TAG, "Checking device: $rawDeviceName, address: $deviceAddress vs targets: ${globalConfig.targetDeviceAddresses}")

        // Ignore devices by default; only enabled devices trigger a popup.
        if (globalConfig.targetDeviceAddresses.isEmpty()) {
            Log.d(TAG, "No target devices configured in whitelist (default ignore all), skipping popup trigger")
            return
        }

        val isMatched = globalConfig.targetDeviceAddresses.any { target ->
            target.equals(deviceAddress, ignoreCase = true) ||
            (deviceAddress.isNotBlank() && target.contains(deviceAddress, ignoreCase = true)) ||
            (rawDeviceName.isNotBlank() && target.contains(rawDeviceName, ignoreCase = true))
        }
        if (!isMatched) {
            Log.d(TAG, "Device $deviceAddress ($rawDeviceName) is not in target whitelist ${globalConfig.targetDeviceAddresses}, ignoring")
            return
        }

        // Read the device-specific popup configuration.
        val deviceConfig = prefsRepo.getDevicePopupConfig(deviceAddress)
        val popupDeviceName = if (deviceConfig.customDeviceName.isNotBlank()) deviceConfig.customDeviceName else rawDeviceName

        Log.d(TAG, "Triggering popup for connected headset: $popupDeviceName ($deviceAddress), theme: ${deviceConfig.animationTheme}")

        // Read the battery level reported by the device.
        val realBattery = getRealBatteryLevel(intent, device)
        if (realBattery in 0..100 && deviceAddress.isNotBlank()) {
            val pending = goAsync()
            val app = context.applicationContext
            historyExecutor.execute {
                try { BatteryHistory(app).use { it.record(deviceAddress, popupDeviceName, realBattery) } }
                finally { pending.finish() }
            }
        }

        val batteryState = DeviceBatteryState(
            deviceName = popupDeviceName,
            deviceAddress = deviceAddress,
            isConnected = true,
            batteryLevel = realBattery,
            isCharging = false
        )

        PopupWindowManager.showPopup(context.applicationContext, batteryState, deviceConfig)
    }

    private fun getRealBatteryLevel(intent: Intent, device: BluetoothDevice?): Int {
        // 1. Read the battery level from the broadcast extras.
        val levelFromIntent = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
        if (levelFromIntent in 0..100) {
            return levelFromIntent
        }

        if (device != null) {
            // 2. Try the hidden BluetoothDevice.getBatteryLevel() method.
            try {
                val method = device.javaClass.getMethod("getBatteryLevel")
                val level = method.invoke(device) as? Int
                if (level != null && level in 0..100) return level
            } catch (e: Exception) {
                // Ignore failures when the device does not report a level.
            }
        }
        return -1
    }
}
