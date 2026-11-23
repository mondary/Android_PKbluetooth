package app.budpop.core

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.vibepop.R

/**
 * The kinds of Bluetooth device BudPop pops up for. Each has its own art and fly-in.
 * [hasBattery] false: usually mains or car powered, so the card shows [powerNote] instead of a missing level.
 * [cooldownMs]: keyboards, mice and controllers reconnect every time they wake, so they don't pop up again for a while.
 */
enum class Kind(
    val key: String,
    val label: String,
    val blurb: String,
    val icon: Int,
    val hasBattery: Boolean = true,
    val powerNote: String = "",
    val cooldownMs: Long = 0L,
    val defaultOn: Boolean = true,
) {
    EARBUDS("earbuds", "Earbuds", "Both buds fly in and cross over", R.drawable.ic_earbuds),
    HEADPHONES("headphones", "Headphones", "Drop in spinning, cups bounce to the beat", R.drawable.ic_headphones),
    SPEAKER("speaker", "Bluetooth speaker", "Lobbed in, bounces twice, then pulses", R.drawable.ic_speaker),
    SOUNDBAR("soundbar", "Soundbar", "Slides in twirling, the EQ lights up", R.drawable.ic_soundbar,
        hasBattery = false, powerNote = "Plugged in"),
    KEYBOARD("keyboard", "Bluetooth keyboard", "Drops onto the desk, keys ripple", R.drawable.ic_keyboard,
        cooldownMs = 15 * 60_000L),
    MOUSE("mouse", "Bluetooth mouse", "Zips in like a cursor and clicks", R.drawable.ic_mouse,
        cooldownMs = 15 * 60_000L),
    CAR("car", "Car Bluetooth adapter", "Drives in, brakes, signal starts", R.drawable.ic_car,
        hasBattery = false, powerNote = "Powered by the car"),
    GAMEPAD("gamepad", "Game controller", "Tumbles in, buttons flash", R.drawable.ic_gamepad,
        cooldownMs = 15 * 60_000L),
    OTHER("other", "Anything else", "Watches aside, any other Bluetooth device", R.drawable.ic_bluetooth,
        defaultOn = false);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** What the popup shows. Most devices report one battery level (or nothing). */
data class BudState(
    val name: String = "Earbuds",
    val battery: Int? = null,
    val charging: Boolean = false,
    val connected: Boolean = true,
    val kind: Kind = Kind.EARBUDS,
)

enum class Style(val key: String, val label: String, val blurb: String) {
    BARE("bare", "Bare minimum", "Icons only: device, battery and close"),
    LOVELY("lovely", "Lovely", "The device flies into a small card at the bottom"),
    FANCIEST("fanciest", "Fanciest", "The device flies in across the whole screen"),
}

object Buds {
    private val SONY_OVER_EAR = Regex("\\b(WH|WI)-", RegexOption.IGNORE_CASE)

    /**
     * Name hints, checked before the Bluetooth class because classes lie: IKEA ENEBY speakers say
     * "headphones", most BLE keyboards and earbuds say nothing at all. First match wins.
     */
    private val HINTS: List<Pair<Kind, Regex>> = listOf(
        Kind.GAMEPAD to "controller|gamepad|game ?pad|xbox|dualsense|dualshock|8bitdo|joy-?con|stadia|backbone",
        Kind.KEYBOARD to "keyboard|keychron|\\bkeys\\b|mx keys|\\bk(3[0-9]{2}|4[0-9]{2}|8[0-9]{2})\\b|\\bkb\\b",
        Kind.MOUSE to "mouse|mice|mx master|mx anywhere|trackball|trackpad|\\bm(3[0-9]{2}|5[0-9]{2}|7[0-9]{2})\\b|pebble",
        Kind.CAR to "\\bcar\\b|carkit|car kit|receiver|transmitter|\\bfm\\b|handsfree kit|\\baux\\b|roav|vehicle|\\bauto\\b|my ?car|uconnect|\\b(mazda|toyota|honda|ford|hyundai|kia|nissan|subaru|bmw|audi|mercedes|volkswagen|vw|chevrolet|tesla)\\b",
        Kind.SOUNDBAR to "sound ?bar|\\bbar\\b|beam|\\barc\\b|\\bht-|\\bsb[0-9]|cinema|theat(er|re)|home ?audio|\\btv\\b|\\bray\\b",
        Kind.SPEAKER to "speaker|eneby|flip|\\bcharge\\b|boom|soundlink|soundcore|\\bclip\\b|jbl go|xtreme|\\bsrs-|\\bxb[0-9]|roam|\\bmove\\b|emberton|stanmore|acton|tribit|bose home|party|pulse|motion|wonder",
        // "QC Earbuds" and "FreeBuds Pro" are earbuds even though they'd match headphone words below.
        Kind.EARBUDS to "bud|\\btws\\b|\\bwf-",
        Kind.HEADPHONES to "headphone|crusher|hesh|\\bqc|quietcomfort|\\bmax\\b|studio|\\bsolo|momentum|\\bath-|\\bmdr|over-?ear|on-?ear|\\btune 7|\\btune 5|\\blive 6|\\bxm[3-6]\\b|\\bnc ?700|\\bh9|\\bh8",
        Kind.EARBUDS to "bud|pod|\\btws\\b|\\bear|free|dots|dime|indy|sesh|push|jlab|go air|\\bpop\\b|\\bwf-|vibe|liberty|elite",
    ).map { (k, p) -> k to Regex(p, RegexOption.IGNORE_CASE) }

    /** Best guess at what a device is, or null for things that shouldn't pop up (phones, computers, watches). */
    fun detect(d: BluetoothDevice): Kind? {
        val nm = runCatching { d.name }.getOrNull().orEmpty()
        val c = runCatching { d.bluetoothClass }.getOrNull()
        val major = c?.majorDeviceClass ?: BluetoothClass.Device.Major.UNCATEGORIZED
        if (major == BluetoothClass.Device.Major.PHONE || major == BluetoothClass.Device.Major.COMPUTER ||
            major == BluetoothClass.Device.Major.NETWORKING || major == BluetoothClass.Device.Major.IMAGING) return null
        if (major == BluetoothClass.Device.Major.WEARABLE || Regex("watch|band|fit\\b", RegexOption.IGNORE_CASE).containsMatchIn(nm)) return null
        // Sony WH-/WI- are HeadSpace's; leave them alone unless picked by hand.
        if (SONY_OVER_EAR.containsMatchIn(nm)) return null
        return fromName(nm) ?: c?.let(::fromClass) ?: Kind.OTHER
    }

    fun fromName(name: String): Kind? = HINTS.firstOrNull { it.second.containsMatchIn(name) }?.first

    private fun fromClass(c: BluetoothClass): Kind? {
        val dc = c.deviceClass
        return when (c.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (dc) {
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Kind.CAR
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> Kind.SPEAKER
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER, BluetoothClass.Device.AUDIO_VIDEO_VCR,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> Kind.SOUNDBAR
                // Plain headphones/headset/hands-free: almost always earbuds these days.
                else -> Kind.EARBUDS
            }
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                (dc and 0x1C) == 0x04 || (dc and 0x1C) == 0x08 -> Kind.GAMEPAD // joystick, gamepad
                (dc and 0xC0) == 0x40 || (dc and 0xC0) == 0xC0 -> Kind.KEYBOARD // keyboard, combo
                (dc and 0xC0) == 0x80 -> Kind.MOUSE                             // pointing device
                else -> Kind.OTHER
            }
            BluetoothClass.Device.Major.TOY -> Kind.GAMEPAD
            else -> null
        }
    }

}
