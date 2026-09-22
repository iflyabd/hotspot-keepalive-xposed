package dev.iflyabd.hotspotkeepalive

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Injects 2 SwitchPreferences into the hotspot settings screen, directly below
 * the stock "Turn off hotspot automatically" toggle:
 *
 * 1. "Keep hotspot on (ignore battery)" -> Settings.Global hotspot_keepalive_ignore_battery
 * 2. "Keep hotspot on (ignore heat)"    -> Settings.Global hotspot_keepalive_ignore_thermal
 *
 * Both default OFF = stock behavior.
 *
 * Works in both hosts:
 * - AOSP Settings: com.android.settings.wifi.tether.WifiTetherSettings
 *   (xml key "wifi_tether_auto_turn_off")
 * - OPlus WirelessSettings (com.oplus.wirelesssettings), which reuses the same
 *   DashboardFragment + preference keys.
 */
object HotspotSettingsHook {
    private const val ANCHOR_KEY = "wifi_tether_auto_turn_off"
    private const val KEY_BATTERY = "hotspot_keepalive_ignore_battery"
    private const val KEY_THERMAL = "hotspot_keepalive_ignore_thermal"
    private const val OBSERVER_TAG = "hotspot_keepalive_observer"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookDashboard(lpparam)
        hookTetherFragmentDirect(lpparam)
    }

    private fun hookDashboard(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            maybeInject(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("HotspotKeepalive: inject failed: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: DashboardFragment hook failed: $e")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStop",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        cleanup(param.thisObject)
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    /** OPlus WirelessSettings may not extend DashboardFragment; hook its tether fragment too. */
    private fun hookTetherFragmentDirect(lpparam: XC_LoadPackage.LoadPackageParam) {
        val fragments = listOf(
            "com.android.settings.wifi.tether.WifiTetherSettings",
            "com.oplus.wirelesssettings.wifi.tether.WifiTetherSettings",
        )
        for (name in fragments) {
            try {
                XposedHelpers.findAndHookMethod(
                    name, lpparam.classLoader, "onStart",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                maybeInject(param.thisObject, lpparam)
                            } catch (e: Throwable) {
                                XposedBridge.log("HotspotKeepalive: direct inject failed: $e")
                            }
                        }
                    },
                )
                XposedBridge.log("HotspotKeepalive: hooked $name.onStart")
            } catch (_: Throwable) {
            }
        }
    }

    private fun maybeInject(fragment: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: return
        // Anchor: stock auto-off toggle. If absent this is not the hotspot screen.
        if (XposedHelpers.callMethod(screen, "findPreference", ANCHOR_KEY) == null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        var added = 0
        if (XposedHelpers.callMethod(screen, "findPreference", KEY_BATTERY) == null) {
            addSwitch(
                lpparam, screen, context,
                KEY_BATTERY,
                "Keep hotspot on (ignore battery)",
                "Block battery-optimisation auto-disable of hotspot",
                HotspotHelper.isIgnoreBattery(context),
            )
            added++
        }
        if (XposedHelpers.callMethod(screen, "findPreference", KEY_THERMAL) == null) {
            addSwitch(
                lpparam, screen, context,
                KEY_THERMAL,
                "Keep hotspot on (ignore heat)",
                "Block overheating auto-disable of hotspot",
                HotspotHelper.isIgnoreThermal(context),
            )
            added++
        }
        if (added == 0) {
            refresh(fragment, screen)
            return
        }

        // Order both directly below the anchor.
        val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
        var anchorIndex = count
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            if ((XposedHelpers.callMethod(p, "getKey") as? String) == ANCHOR_KEY) {
                anchorIndex = i
                break
            }
        }
        val battery = XposedHelpers.callMethod(screen, "findPreference", KEY_BATTERY)
        val thermal = XposedHelpers.callMethod(screen, "findPreference", KEY_THERMAL)
        // Shift everything after the anchor down by 2.
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            val key = XposedHelpers.callMethod(p, "getKey") as? String
            if (key == KEY_BATTERY || key == KEY_THERMAL) continue
            val order = XposedHelpers.callMethod(p, "getOrder") as Int
            if (order > anchorIndex) XposedHelpers.callMethod(p, "setOrder", order + 2)
        }
        if (battery != null) XposedHelpers.callMethod(battery, "setOrder", anchorIndex + 1)
        if (thermal != null) XposedHelpers.callMethod(thermal, "setOrder", anchorIndex + 2)

        if (XposedHelpers.getAdditionalInstanceField(fragment, OBSERVER_TAG) == null) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    refresh(fragment, screen)
                }
            }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_BATTERY), false, observer,
            )
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_THERMAL), false, observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, OBSERVER_TAG, observer)
        }
        XposedBridge.log("HotspotKeepalive: injected 2 toggles below hotspot auto-off")
    }

    private fun addSwitch(
        lpparam: XC_LoadPackage.LoadPackageParam,
        screen: Any,
        context: Context,
        key: String,
        title: String,
        summary: String,
        checked: Boolean,
    ) {
        val switchClass = XposedHelpers.findClass(
            "androidx.preference.SwitchPreferenceCompat", lpparam.classLoader,
        )
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        XposedHelpers.callMethod(pref, "setKey", key)
        XposedHelpers.callMethod(pref, "setTitle", title)
        XposedHelpers.callMethod(pref, "setSummary", summary)
        XposedHelpers.callMethod(pref, "setChecked", checked)
        val listenerClass = XposedHelpers.findClass(
            "androidx.preference.Preference\$OnPreferenceChangeListener", lpparam.classLoader,
        )
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            lpparam.classLoader, arrayOf(listenerClass),
        ) { _, _, args ->
            val newValue = args!![1] as Boolean
            Settings.Global.putInt(context.contentResolver, key, if (newValue) 1 else 0)
            XposedBridge.log("HotspotKeepalive: $key -> $newValue")
            true
        }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", proxy)
        XposedHelpers.callMethod(screen, "addPreference", pref)
    }

    private fun refresh(fragment: Any, screen: Any) {
        try {
            val context = XposedHelpers.callMethod(screen, "getContext") as Context
            (XposedHelpers.callMethod(screen, "findPreference", KEY_BATTERY))?.let {
                XposedHelpers.callMethod(it, "setChecked", HotspotHelper.isIgnoreBattery(context))
            }
            (XposedHelpers.callMethod(screen, "findPreference", KEY_THERMAL))?.let {
                XposedHelpers.callMethod(it, "setChecked", HotspotHelper.isIgnoreThermal(context))
            }
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: refresh failed: $e")
        }
    }

    private fun cleanup(fragment: Any) {
        val observer = XposedHelpers.getAdditionalInstanceField(fragment, OBSERVER_TAG)
            as? ContentObserver ?: return
        try {
            val context = XposedHelpers.callMethod(fragment, "getContext") as? Context
            context?.contentResolver?.unregisterContentObserver(observer)
        } catch (_: Throwable) {
        }
        XposedHelpers.removeAdditionalInstanceField(fragment, OBSERVER_TAG)
    }
}
