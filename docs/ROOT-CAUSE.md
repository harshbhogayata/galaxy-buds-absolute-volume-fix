# Root cause, from the firmware

Source: the Bluetooth module on a Galaxy S24 running One UI 9 (`/apex/com.android.bt/app/Bluetooth@CP2A.260605.016/Bluetooth.apk`, `/apex/com.android.bt/lib64/libbluetooth_jni.so`) and Samsung Settings (`/system/priv-app/SecSettings/SecSettings.apk`). All of these can be read by the adb shell. They were pulled with `adb pull` and read with `dexdump -d` (Android SDK build-tools) plus a strings scan of the native library.

Below, Samsung's code is paraphrased from bytecode. Method and string names are exact.

## 1. The "Samsung dual buds" type

`com.android.bluetooth.Utils.defineSsBudsType(BluetoothDevice)`:

```
if getPrimaryDeviceOfSsBuds(device) != null:
    if SystemProperties.getBoolean("persist.bluetooth.leaudio.dualmodeoff", false):
        log "defineSsBudsType : DISABLE_SS_DUAL_BUDS"; return 0
    return 1
return 0
```

It's only called from places that **first check whether the device's UUID list contains `BluetoothUuid.LE_AUDIO`**:

- `RemoteDevices.devicePropertyChangedCallback`, for `BT_PROPERTY_UUIDS` and `BT_PROPERTY_MANUFACTURER_DATA` ("change SsBudsType … from BT_PROPERTY_UUIDS / BT_PROPERTY_MANUFACTURER_DATA")
- `RemoteDevices.notifyUuids` ("MESSAGE_NOTIFY_UUIDS")
- `AdapterService.updatetSsBudsType`, called from `CsipSetCoordinatorService.messageFromNative` and `LeAudioService.handleGroupNodeAdded`, which only happens once LE Audio is in use

So a buds pairing whose saved services don't include LE Audio stays at type 0.

`AdapterService.isSsDualBuds(device)` is just `getSsBudsType(device) == 1`.

## 2. What type 1 changes

**LE Audio is forced back on at every classic connect.** `A2dpService.connectionStateChanged`, on reaching CONNECTED, calls `A2dpService.connectLeAudio(device)`:

```
if !isSsDualBuds(device): log "connectLeAudio : No SsDualBuds"; return
if !isHfpConnected(device): log "connectLeAudio : Hfp not connected"; return
LeAudioService.connect(device)   // sets the LE Audio connection policy back to ALLOWED (100)
```

(`HeadsetService.connectLeAudio` does the same from the call-audio side.) That's why setting the LE Audio connection policy to FORBIDDEN never lasts past a reconnect.

**The native "dual mode" flag.** `LeAudioStateMachine.updateSsDualBuds()` → `LeAudioNativeInterface.setSsDualBuds(device, isSsDualBuds)` → native `SetSsDualBuds`.

**AVRCP ignores "Disable absolute volume".** In `libbluetooth_jni.so`, `system/profile/avrcp/connection_handler.cc` `AbsoluteVolumeDisabled()` (around line 95–98):

```
"Absolute volume disabled by property"          // persist.bluetooth.disableabsvol == true
"ignore property because of dual mode device"   // Samsung addition: absolute volume stays enabled
```

The result is `SetRcFeatures: feature=3`, `DEVICE_VOLUME_BEHAVIOR_ABSOLUTE`.

## 3. The only intended off-switch

`persist.bluetooth.leaudio.dualmodeoff`. It's read by `Utils.defineSsBudsType` and `Utils.isPrimaryDeviceOfSsDualBuds`. It's written only by:

- `com.samsung.android.settings.development.BluetoothDualModeDisablePreferenceController` (Developer options key `bluetooth_dualmode_disable`). Its `isAvailable()` returns `IS_PRODUCT_DEV && LEAUDIO_ENABLED`, so it only appears on Samsung's internal dev builds. On a retail phone it's hidden.
- Nothing else on the phone. `setprop persist.bluetooth.leaudio.dualmodeoff true` from adb fails ("Failed to set property"), like every `persist.bluetooth.*` property.

## 4. Why the record gains LE Audio

Pairing from Samsung's pop-up (`com.samsung.android.easysetup`, `semCreateBond`) or from the Bluetooth settings list (`createBond(0)`, transport auto) pairs over LE when the phone has seen the buds' LE advertisement.

Even an explicit classic pairing (`createBond(TRANSPORT_BREDR)`) continues with:

1. `btm_sec_perform_ctkd: Started SMP over BR/EDR`: cross-transport key derivation.
2. `btif_dm_ble_auth_cmpl_evt: scheduling GATT discovery over LE`
3. An LE connection plus GATT discovery returning `BATTERY, VolumeControl, CoordinatedSet, LE_AUDIO, BASS`.
4. The Java side merges those into the saved services, `setSsBudsType(1)`, and Samsung auto-pairs the second bud through CSIP.

The working record in September–October had LE keys (the bud was on the LE resolving list at every boot) but no LE services. That's consistent with LE discovery being interrupted at pairing time. The fix recreates exactly that.

## 5. Developer options reset

`com.android.settings.development.BluetoothAbsoluteVolumePreferenceController.onDeveloperOptionsSwitchDisabled()`:

```
SystemProperties.set("persist.bluetooth.disableabsvol", "false")
```

`BluetoothAvrcpVersionPreferenceController` has no such reset, so AVRCP 1.3 survives. Re-enabling Developer options doesn't set the property back either: `updateState` only reads it.

`settings put global development_settings_enabled 0` from adb hides Developer options without running this controller.

## 6. Settings that no longer exist or are ignored on One UI 9

- `Settings.Global bluetooth_disabled_profiles`: not referenced anywhere in the One UI 9 Bluetooth app. `Config.init` decides profiles from `persist.bluetooth.leaudio_dynamic_switcher.mode` and `ro.bluetooth.leaudio_switcher.supported`, plus hardware ISO support.
- `Settings.Global bluetooth_interoperability_list`: no reader. The interop database is native and file-based (`/data/misc/bluedroid/`, root only).
- Bluetooth aconfig / `device_config` flags: none covers absolute volume or dual mode.
