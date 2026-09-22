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
 * Injects 3 SwitchPreferences into the hotspot settings screen, directly below
 * the stock "Turn off hotspot automatically" toggle:
 *
 * 1. "Keep hotspot on (ignore battery)" -> Settings.Global hotspot_keepalive_ignore_battery
 * 2. "Keep hotspot on (ignore heat)"    -> Settings.Global hotspot_keepalive_ignore_thermal
 * 3. "Turn on hotspot at boot"          -> Settings.Global hotspot_keepalive_onboot
 *
 * All default OFF = stock behavior.
 *
 * Works in both hosts:
 * - AOSP Settings: com.android.settings.wifi.tether.WifiTetherSettings
 *   (xml key "wifi_tether_auto_turn_off")
 * - OPlus WirelessSettings (com.oplus.wirelesssettings), which reuses the same
 *   preference keys. A PreferenceFragmentCompat fallback covers OPlus custom
 *   fragment base classes.
 */
object HotspotSettingsHook {
    private const val ANCHOR_KEY = "wifi_tether_auto_turn_off"
    private const val KEY_BATTERY = "hotspot_keepalive_ignore_battery"
    private const val KEY_THERMAL = "hotspot_keepalive_ignore_thermal"
    private const val KEY_ONBOOT = "hotspot_keepalive_onboot"
    private const val OBSERVER_TAG = "hotspot_keepalive_observer"

    /** Stock auto-off keys: AOSP + OPlus variants. Injection anchors below whichever is present. */
    private val ANCHORS = listOf(
        "wifi_tether_auto_turn_off",
        "wifi_ap_timeout_auto_close",
        "oplus_wifi_ap_timeout_auto_close",
        "static_ap_wifi_auto_close_switch",
    )

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val injectHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    maybeInject(param.thisObject, lpparam)
                } catch (e: Throwable) {
                    XposedBridge.log("HotspotKeepalive: inject failed: $e")
                }
            }
        }
        val cleanupHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                cleanup(param.thisObject)
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStart",
                injectHook,
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: DashboardFragment hook failed: $e")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStop",
                cleanupHook,
            )
        } catch (_: Throwable) {
        }
        // Fallback for OPlus custom fragment base classes (idempotent).
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.preference.PreferenceFragmentCompat",
                lpparam.classLoader,
                "onStart",
                injectHook,
            )
            XposedBridge.log("HotspotKeepalive: PreferenceFragmentCompat fallback hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: PreferenceFragmentCompat hook failed: $e")
        }
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.preference.PreferenceFragmentCompat",
                lpparam.classLoader,
                "onStop",
                cleanupHook,
            )
        } catch (_: Throwable) {
        }
        // Generic fragment discovery: log hotspot-ish screens we don't otherwise cover.
        // (COUIPanelFragment declares no onStart of its own, so it is covered here.)
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.fragment.app.Fragment",
                lpparam.classLoader,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            logScreen("frag", param.thisObject)
                        } catch (_: Throwable) {
                        }
                    }
                },
            )
            XposedBridge.log("HotspotKeepalive: androidx Fragment discovery hook installed")
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: androidx Fragment hook failed: $e")
        }
        hookTetherFragmentDirect(lpparam)
    }

    /** OPlus WirelessSettings may use its own fragment; hook tether fragments directly too. */
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
        logScreen("inject", fragment)
        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: return
        val keys = mutableSetOf<String>()
        try {
            val n = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
            for (i in 0 until n) {
                val p = XposedHelpers.callMethod(screen, "getPreference", i)
                (XposedHelpers.callMethod(p, "getKey") as? String)?.let { keys += it }
            }
        } catch (_: Throwable) {
        }
        // Anchor: stock auto-off toggle (AOSP or OPlus key). On OOS the
        // WifiTetherSettings screen only has group_name/prompt/advance_group
        // (OPlus removed the AOSP toggle), so fall back to anchoring below
        // advance_group on that exact screen.
        // NOTE: we never modify or remove the anchor — only add below it — so the
        // stock hotspot switch keeps working exactly as before.
        val anchor = ANCHORS.firstOrNull { key ->
            XposedHelpers.callMethod(screen, "findPreference", key) != null
        } ?: if (keys.containsAll(listOf("group_name", "prompt", "advance_group"))) {
            "advance_group"
        } else {
            return
        }
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
        if (XposedHelpers.callMethod(screen, "findPreference", KEY_ONBOOT) == null) {
            addSwitch(
                lpparam, screen, context,
                KEY_ONBOOT,
                "Turn on hotspot at boot",
                "Automatically start hotspot after every reboot",
                HotspotHelper.isOnbootEnabled(context),
            )
            added++
        }
        if (added == 0) {
            refresh(fragment, screen)
            return
        }

        // Order all three directly below the anchor.
        val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
        var anchorOrder = Int.MAX_VALUE
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            if ((XposedHelpers.callMethod(p, "getKey") as? String) == anchor) {
                anchorOrder = XposedHelpers.callMethod(p, "getOrder") as Int
                break
            }
        }
        val ours = setOf(KEY_BATTERY, KEY_THERMAL, KEY_ONBOOT)
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            val key = XposedHelpers.callMethod(p, "getKey") as? String
            if (key in ours) continue
            val order = XposedHelpers.callMethod(p, "getOrder") as Int
            if (order > anchorOrder) XposedHelpers.callMethod(p, "setOrder", order + 3)
        }
        (XposedHelpers.callMethod(screen, "findPreference", KEY_BATTERY))?.let {
            XposedHelpers.callMethod(it, "setOrder", anchorOrder + 1)
        }
        (XposedHelpers.callMethod(screen, "findPreference", KEY_THERMAL))?.let {
            XposedHelpers.callMethod(it, "setOrder", anchorOrder + 2)
        }
        (XposedHelpers.callMethod(screen, "findPreference", KEY_ONBOOT))?.let {
            XposedHelpers.callMethod(it, "setOrder", anchorOrder + 3)
        }
        XposedBridge.log("HotspotKeepalive: injected 3 toggles below hotspot auto-off")

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
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(KEY_ONBOOT), false, observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, OBSERVER_TAG, observer)
        }
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
            (XposedHelpers.callMethod(screen, "findPreference", KEY_ONBOOT))?.let {
                XposedHelpers.callMethod(it, "setChecked", HotspotHelper.isOnbootEnabled(context))
            }
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: refresh failed: $e")
        }
    }

    /** Discovery: log fragment class + preference keys so we can anchor correctly. */
    private fun logScreen(tag: String, fragment: Any) {
        try {
            val cls = fragment.javaClass.name
            val interesting = cls.contains("tether", true) || cls.contains("hotspot", true) ||
                cls.contains("wireless", true) || cls.contains("softap", true) ||
                cls.contains("wifiap", true) || cls.contains("panel", true) ||
                cls.contains("wlan", true)
            val screen = try {
                XposedHelpers.callMethod(fragment, "getPreferenceScreen")
            } catch (_: Throwable) {
                null
            }
            if (screen == null) {
                if (interesting) {
                    XposedBridge.log("HotspotKeepalive: [$tag] $cls has NO preference screen")
                }
                return
            }
            val keys = mutableListOf<String>()
            try {
                val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
                for (i in 0 until count) {
                    val p = XposedHelpers.callMethod(screen, "getPreference", i)
                    keys += (XposedHelpers.callMethod(p, "getKey") as? String)
                        ?: "<nokey>:${p.javaClass.name.substringAfterLast('.')}"
                }
            } catch (e: Throwable) {
                keys += "ITER_FAIL:$e"
            }
            val present = ANCHORS.filter { a -> keys.any { it == a } }
            if (interesting || present.isNotEmpty()) {
                XposedBridge.log(
                    "HotspotKeepalive: [$tag] $cls anchors=$present " +
                        "keys=${keys.joinToString(",").take(1500)}",
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: [$tag] logScreen failed: $e")
        }
    }

    private fun cleanup(fragment: Any) {        val observer = XposedHelpers.getAdditionalInstanceField(fragment, OBSERVER_TAG)
            as? ContentObserver ?: return
        try {
            val context = XposedHelpers.callMethod(fragment, "getContext") as? Context
            context?.contentResolver?.unregisterContentObserver(observer)
        } catch (_: Throwable) {
        }
        XposedHelpers.removeAdditionalInstanceField(fragment, OBSERVER_TAG)
    }
}
