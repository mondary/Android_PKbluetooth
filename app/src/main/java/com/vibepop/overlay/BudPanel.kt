package com.vibepop.overlay

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.budpop.core.*
import app.budpop.popup.*
import com.vibepop.data.model.DeviceBatteryState
import com.vibepop.data.model.PopupConfig

/** One card/controller for both the overlay window and the lock-screen activity. */
class BudPanel(ctx: Context, config: PopupConfig, device: DeviceBatteryState,
               resize: (Boolean) -> Unit, private val remove: () -> Unit) {
    companion object {
        fun matches(key: String) = key.startsWith("budpop:")
        fun label(key: String): String {
            val parts = key.split(':')
            val style = Style.entries.find { it.key == parts.getOrNull(1) } ?: Style.LOVELY
            return "${style.label} · ${Kind.of(parts.getOrNull(2))?.label ?: "Auto"}"
        }
    }
    private val parts = config.animationTheme.split(':')
    private val kind = Kind.of(parts.getOrNull(2)) ?: runCatching {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(device.deviceAddress)?.let(Buds::detect)
    }.getOrNull() ?: Buds.fromName(device.deviceName) ?: Kind.EARBUDS
    private var state = BudState(config.customDeviceName.ifBlank { device.deviceName.ifBlank { kind.label } },
        device.batteryLevel.takeIf { it in 0..100 }, device.isCharging, device.isConnected, kind)
    private val timer = Handler(Looper.getMainLooper())
    private val delay = config.autoDismissSeconds.coerceAtLeast(2) * 1000L
    private val close = Runnable { dismiss() }
    private var closing = false
    private val actions = object : PopupActions {
        override fun close() = dismiss()
        override fun fullScreen(on: Boolean) = resize(on)
        override fun touched() {
            if (!closing) { timer.removeCallbacks(close); timer.postDelayed(close, delay) }
        }
    }
    val card: PopupView = when (parts.getOrNull(1)) {
        Style.BARE.key -> BareCard(ctx, actions, kind)
        Style.FANCIEST.key -> FlyCard(ctx, actions, mini = false, kind)
        else -> FlyCard(ctx, actions, mini = true, kind)
    }
    fun start() { card.bind(state); card.enter(); actions.touched() }
    fun battery(value: Int) { state = state.copy(battery = value.takeIf { it in 0..100 }); card.bind(state) }
    fun dismiss() {
        if (!closing) { dispose(); card.exit(remove) }
    }
    fun dispose() { closing = true; timer.removeCallbacks(close) }
}
