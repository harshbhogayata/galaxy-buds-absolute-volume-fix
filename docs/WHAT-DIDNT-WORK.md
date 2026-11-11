# What doesn't work on One UI 9 (and why)

Everything here was tried on a Galaxy S24 with One UI 9 and Buds3 Pro, so you don't have to.

| Attempt | Result | Why |
|---|---|---|
| Developer options: Disable absolute volume + AVRCP 1.3, restart, re-pair | Works on the first connection only | The re-pair stores `LE_AUDIO`. From the next connect the buds are "dual buds" and the setting is ignored. Still required for the fix. |
| Reset Wi-Fi and Bluetooth settings, pair with the case button | First connection only | Same: the new record has LE Audio. |
| `settings put global bluetooth_disable_abs_volume 1` | No effect on its own | The stack reads `persist.bluetooth.disableabsvol`, which only Settings (system) can write. |
| `setprop persist.bluetooth.disableabsvol true` / `…avrcpversion` / `…leaudio.dualmodeoff` | `Failed to set property` | SELinux: the shell can't write `persist.bluetooth.*`. |
| `settings put system/global bluetooth_le_audio_enabled 0` | No effect | Dual mode ignores it. |
| `settings put global bluetooth_disabled_profiles 12582912` | No effect on One UI 9 | The One UI 9 Bluetooth app no longer reads this key. LE Audio still starts. |
| `settings put global bluetooth_interoperability_list "<OUI>,2"` | No effect | No reader on One UI 9. The interop database is native and root-only. |
| `aflags` / `device_config` Bluetooth flags | `aflags`: must be root; no relevant `device_config` flag | No flag covers absolute volume or dual mode. |
| Poweramp's `service call audio 178 …` (setDeviceVolumeBehavior) | Rejected | One UI 9 changed the binder parcel layout. |
| Turn UHQ audio on | Not enough | `Dual mode enable state is: false`, but the type stays 1 and AVRCP still ignores the property. Fine to keep on. |
| Forbid the LE Audio connection policy (shell helper) | Undone on the next connect | `A2dpService.connectLeAudio` sets it back to 100 for dual buds. |
| `setDeviceVolumeBehavior(VARIABLE)` + Samsung `setVolumeControlSupport(false)` | Works until the next reconnect | Reset at each connect. With a watcher re-applying it, phone→buds is separate, but the buds' gesture still moves the phone volume. A bandage. |
| `BluetoothA2dp.setAbsoluteVolumeControl(false)` (Samsung) | Returns true, value unchanged | Ignored. |
| `semSetAudioType` 2 → 1 | Saved, no effect | Dual mode still runs. |
| Disable/uninstall the Buds plugin | Breaks Buds settings and Bluetooth settings | Bluetooth settings calls the plugin's `WelcomeActivity`. Restore with `cmd package install-existing`. |
| Pair from Settings with the case button | Stores LE_AUDIO | Settings pairs over LE once the buds' LE advertisement has been seen. |
| `createBond(TRANSPORT_BREDR)` from the shell, no cut | Stores LE_AUDIO | Key derivation, then `scheduling GATT discovery over LE`. |
| Cut with `cmd bluetooth_manager disable` | Stores LE_AUDIO | The adapter only drops to `BLE ON`, and LE discovery finishes. |
| Cut at key-derivation start | Pairing discarded | Pairing isn't finalised yet. |
| Root | Would work (`persist.bluetooth.leaudio.dualmodeoff=true`) | Not tested. Trips Knox. |
