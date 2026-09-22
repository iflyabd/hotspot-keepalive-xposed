package dev.iflyabd.hotspotkeepalive

import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Blocks automatic hotspot shutdown for the two user toggles:
 *
 * Toggle 1 (KEY_IGNORE_BATTERY): battery-optimisation / power-save auto-disable.
 * Toggle 2 (KEY_IGNORE_THERMAL): overheating (overwork/thermal) auto-disable.
 *
 * Both default OFF = stock behavior.
 *
 * Layers:
 * A. OPlus services (run inside com.oplus.wirelesssettings / system):
 *    - WifiApCloseService        (WS_WLAN_WifiApCloseService): generic auto-close service
 *    - WifiApOverworkNotificationReceiver (overwork = heat/time protection)
 *    Blocked only when the matching toggle is ON.
 * B. AOSP framework (android / system_server):
 *    - SoftApManager auto-shutdown checks: hook every method whose name hints at
 *      shutdown/stop/low-battery/thermal/power and veto the shutdown when the
 *      matching toggle is ON. We detect the reason from the method name + stack
 *      so the idle-timeout ("no devices connected") toggle keeps working and
 *      manual user switch-off is never blocked.
 */
object HotspotFrameworkHook {

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookOplusServices(lpparam)
        if (lpparam.packageName == "android") {
            hookSoftApManager(lpparam)
            hookWifiServiceStop(lpparam)
        }
    }

    private fun currentApp(): Context? {
        return try {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }

    private fun flags(): Pair<Boolean, Boolean> {
        val ctx = currentApp() ?: return false to false
        return try {
            val cr = ctx.contentResolver
            val battery = Settings.Global.getInt(cr, HotspotHelper.KEY_IGNORE_BATTERY, 0) == 1
            val thermal = Settings.Global.getInt(cr, HotspotHelper.KEY_IGNORE_THERMAL, 0) == 1
            battery to thermal
        } catch (_: Throwable) {
            false to false
        }
    }

    private fun stackHas(vararg needles: String): Boolean {
        val trace = Thread.currentThread().stackTrace.joinToString("|") { it.toString() }
        return needles.any { trace.contains(it, ignoreCase = true) }
    }

    // ---------- A. OPlus services ----------

    private fun hookOplusServices(lpparam: XC_LoadPackage.LoadPackageParam) {
        // WifiApCloseService: block automatic close intents when either toggle is on.
        for (cls in listOf(
            "com.oplus.wirelesssettings.wifi.tether.WifiApCloseService",
            "com.oplus.wirelesssettings.wifi.tether.WifiApOverworkNotificationReceiver",
        )) {
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                for (m in clazz.declaredMethods) {
                    val name = m.name
                    val isClose = name == "onStartCommand" || name == "onHandleIntent" ||
                        name == "onReceive" || name.lowercase().contains("close") ||
                        name.lowercase().contains("overwork") || name.lowercase().contains("stop")
                    if (!isClose) continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val (ignoreBattery, ignoreThermal) = flags()
                            if (!ignoreBattery && !ignoreThermal) return
                            val thermalFlavor = name.contains("Overwork", ignoreCase = true) ||
                                cls.contains("Overwork", ignoreCase = true)
                            // Overwork receiver is heat/time protection -> thermal toggle.
                            // Generic close service -> either toggle blocks.
                            val shouldBlock = if (thermalFlavor) ignoreThermal else (ignoreBattery || ignoreThermal)
                            if (!shouldBlock) return
                            XposedBridge.log("HotspotKeepalive: blocked $cls.$name")
                            val rt0 = (param.method as? java.lang.reflect.Method)?.returnType
                            when (rt0) {
                                Int::class.javaPrimitiveType, Integer::class.java -> param.result = 2 // START_NOT_STICKY
                                Boolean::class.javaPrimitiveType -> param.result = false
                                else -> param.result = null
                            }
                        }
                    })
                    XposedBridge.log("HotspotKeepalive: hooked $cls.$name")
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---------- B. AOSP framework ----------

    private fun hookSoftApManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.server.wifi.SoftApManager", lpparam.classLoader,
            )
            for (m in clazz.declaredMethods) {
                val n = m.name
                val ln = n.lowercase()
                val looksShutdown = ln.contains("shutdown") || ln.contains("shouldstop") ||
                    ln.contains("autoshutdown") || ln.contains("checksoftap") ||
                    ln.contains("lowbattery") || ln.contains("thermal") ||
                    ln.contains("power") || ln.contains("overwork") ||
                    ln.contains("timeout")
                if (!looksShutdown) continue
                // Only veto boolean decisions / void shutdown executors; never touch
                // config getters (getSoftApConfiguration etc.).
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val (ignoreBattery, ignoreThermal) = flags()
                        if (!ignoreBattery && !ignoreThermal) return
                        val reason = classifyReason(n)
                        val block = when (reason) {
                            Reason.BATTERY -> ignoreBattery
                            Reason.THERMAL -> ignoreThermal
                            Reason.IDLE -> false // never block idle-timeout here
                            Reason.UNKNOWN -> {
                                // Unknown shutdown path: block only if stack smells of
                                // battery/power or thermal/heat/overwork AND matching toggle on.
                                val batterySmell = stackHas("battery", "power", "powersave", "save")
                                val thermalSmell = stackHas("thermal", "heat", "overwork", "temperatur")
                                (batterySmell && ignoreBattery) || (thermalSmell && ignoreThermal)
                            }
                        }
                        if (!block) return
                        XposedBridge.log("HotspotKeepalive: veto SoftApManager.$n (reason=$reason)")
                        val rt = (param.method as? java.lang.reflect.Method)?.returnType
                        if (rt == Boolean::class.javaPrimitiveType || rt == java.lang.Boolean::class.java) {
                            // check* / should* returning "should shut down?" -> answer no.
                            if (n.startsWith("check") || n.startsWith("should") || n.startsWith("is")) {
                                param.result = false
                            }
                        } else if (rt == Void.TYPE) {
                            param.result = null // skip shutdown executor
                        }
                    }
                })
                XposedBridge.log("HotspotKeepalive: hooked SoftApManager.$n")
            }
        } catch (e: Throwable) {
            XposedBridge.log("HotspotKeepalive: SoftApManager hook failed: $e")
        }
    }

    /** Last-resort guard: WifiServiceImpl.stopSoftAp with battery/thermal smell. */
    private fun hookWifiServiceStop(lpparam: XC_LoadPackage.LoadPackageParam) {
        for (cls in listOf("com.android.server.wifi.WifiServiceImpl")) {
            try {
                val clazz = XposedHelpers.findClass(cls, lpparam.classLoader)
                for (m in clazz.declaredMethods) {
                    val ln = m.name.lowercase()
                    if (!(ln.contains("stop") && ln.contains("softap")) && ln != "stopsoftap") continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val (ignoreBattery, ignoreThermal) = flags()
                            if (!ignoreBattery && !ignoreThermal) return
                            // Manual user toggle-off comes from Settings UI without
                            // battery/thermal frames; never block that path.
                            val batterySmell = stackHas("battery", "powersave", "power", "low")
                            val thermalSmell = stackHas("thermal", "heat", "overwork", "temperatur")
                            val block = (batterySmell && ignoreBattery) || (thermalSmell && ignoreThermal)
                            if (!block) return
                            XposedBridge.log("HotspotKeepalive: blocked $cls.${m.name} (auto)")
                            param.result = null
                        }
                    })
                    XposedBridge.log("HotspotKeepalive: hooked $cls.${m.name}")
                }
            } catch (e: Throwable) {
                XposedBridge.log("HotspotKeepalive: $cls hook failed: $e")
            }
        }
    }

    private enum class Reason { BATTERY, THERMAL, IDLE, UNKNOWN }

    private fun classifyReason(method: String): Reason {
        val l = method.lowercase()
        if (l.contains("battery") || l.contains("power") || l.contains("low")) return Reason.BATTERY
        if (l.contains("thermal") || l.contains("heat") || l.contains("overwork") || l.contains("temperatur")) {
            return Reason.THERMAL
        }
        if (l.contains("idle") || l.contains("timeout") || l.contains("inactiv")) return Reason.IDLE
        return Reason.UNKNOWN
    }
}
