package dev.iflyabd.hotspotkeepalive

import android.content.Context
import android.provider.Settings

/** Module toggles stored in Settings.Global so all hooked processes share them. */
object HotspotHelper {
    const val KEY_IGNORE_BATTERY = "hotspot_keepalive_ignore_battery"
    const val KEY_IGNORE_THERMAL = "hotspot_keepalive_ignore_thermal"

    fun isIgnoreBattery(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_IGNORE_BATTERY, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    fun isIgnoreThermal(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, KEY_IGNORE_THERMAL, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }
}
