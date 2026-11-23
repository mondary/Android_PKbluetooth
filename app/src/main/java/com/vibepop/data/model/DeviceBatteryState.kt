package com.vibepop.data.model

/**
 * Bluetooth device connection and reported battery state.
 */
data class DeviceBatteryState(
    val deviceName: String = "",
    val deviceAddress: String = "",
    val isConnected: Boolean = true,
    val batteryLevel: Int = -1,          // 0..100; -1 means unknown or not reported.
    val isCharging: Boolean = false
) : java.io.Serializable {
    companion object {
        fun mock(): DeviceBatteryState = DeviceBatteryState(
            deviceName = "",
            deviceAddress = "00:11:22:33:44:55",
            isConnected = true,
            batteryLevel = 85,
            isCharging = false
        )
    }
}
