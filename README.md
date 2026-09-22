# Hotspot Keepalive (LSPosed)

LSPosed module that adds **2 toggles** to the hotspot (Wi-Fi tether) settings,
directly below the stock "Turn off hotspot automatically" switch:

1. **Keep hotspot on (ignore battery)** — blocks battery-optimisation /
   power-save auto-disable of hotspot.
2. **Keep hotspot on (ignore heat)** — blocks overheating (overwork/thermal)
   auto-disable of hotspot.

Both default OFF = stock behavior. Toggles are stored in
`Settings.Global.hotspot_keepalive_ignore_battery` /
`Settings.Global.hotspot_keepalive_ignore_thermal`.

## What it hooks

- Settings UI: `WifiTetherSettings` (`wifi_tether_auto_turn_off` anchor) in both
  `com.android.settings` and `com.oplus.wirelesssettings`.
- OPlus: `WifiApCloseService` + `WifiApOverworkNotificationReceiver`.
- Framework (`android`/system_server): `SoftApManager` shutdown checks and
  `WifiServiceImpl.stopSoftAp`, vetoed only when the stack reason matches the
  enabled toggle — idle-timeout and manual switch-off are never blocked.

## Scope (LSPosed)

- `com.android.settings`
- `com.oplus.wirelesssettings`
- `android` (system_server)

## Build

Pushes to `main` build a debug APK via GitHub Actions (artifact
`hotspot-keepalive-debug-apk`). Tags `v*` create a GitHub release.

## Test

1. Enable module in LSPosed for Settings + WirelessSettings + System.
2. Reboot (ask before rebooting).
3. Hotspot settings -> below "Turn off hotspot automatically" enable the two
   "Keep hotspot on" toggles.
4. Test low-battery saver and heat soak; hotspot should stay up.
   Check LSPosed logs for `HotspotKeepalive`.
