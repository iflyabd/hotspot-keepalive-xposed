package dev.iflyabd.hotspotkeepalive

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HotspotKeepaliveModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("HotspotKeepalive: ${lpparam.packageName} / ${lpparam.processName}")
        when (lpparam.packageName) {
            "com.android.settings", "com.oplus.wirelesssettings" -> {
                HotspotSettingsHook.init(lpparam)
                HotspotFrameworkHook.init(lpparam)
            }
            "android" -> {
                HotspotFrameworkHook.init(lpparam)
            }
        }
    }
}
