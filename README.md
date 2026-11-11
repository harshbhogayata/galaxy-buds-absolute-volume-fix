# Galaxy Buds absolute volume on One UI 9 — root cause and non-root fix

Tested on: Galaxy S24 (SM-S921B), One UI 9 / Android 17 (build S921BXXSIEZJ1), Galaxy Buds3 Pro.
Should apply to other Samsung phones on One UI 8.5–9 and other LE Audio Galaxy Buds (Buds2 Pro, Buds3, Buds3 Pro), but only the setup above was tested.

## Symptom

- Developer options → **Disable absolute volume** ON and **Bluetooth AVRCP version** 1.3 are set.
- The first connection after pairing works: phone and buds volume are separate.
- Every reconnect after that (case close/open, Bluetooth toggle) goes back to synced (absolute) volume, and EQ apps with Direct Volume Control (Poweramp Equalizer, Wavelet) sound wrong.
- Proof command:
  ```
  adb shell dumpsys audio | grep -i -E "mAvrcpAbsVolSupported|setDeviceVolumeBehavior|avrcpSupportsAbsoluteVolume"
  ```
  Broken: `DEVICE_VOLUME_BEHAVIOR_ABSOLUTE`, `support=true`. Working: `DEVICE_VOLUME_BEHAVIOR_VARIABLE`, `support=false`.

## Root cause

Found by reading the phone's own Bluetooth module (`/apex/com.android.bt`, `Bluetooth.apk` + `libbluetooth_jni.so`) and its Bluetooth logs.

1. Samsung's Bluetooth service classifies a device as **"Samsung dual buds"** (`mSsBudsType = 1`, `Utils.defineSsBudsType`) whenever the device's **saved service list (UUIDs) contains `LE_AUDIO`**. The only override is the property `persist.bluetooth.leaudio.dualmodeoff=true`, which on retail firmware can't be set from adb and has no visible switch: the Developer options entry for it only shows on Samsung's internal dev builds.
2. For "dual buds":
   - `A2dpService.connectLeAudio` sets the buds' LE Audio connection policy back to *allowed* and connects LE Audio **every time classic audio connects** (log: `A2DPSVC-connectLeAudio`).
   - The native AVRCP code reads your "Disable absolute volume" setting and then **deliberately ignores it**:
     ```
     AbsoluteVolumeDisabled: Absolute volume disabled by property
     AbsoluteVolumeDisabled: ignore property because of dual mode device
     SetRcFeatures: feature=3
     ```
3. Whether `LE_AUDIO` ends up in the saved record depends on **how the buds were paired**. Pairing from Samsung's pop-up or from the Bluetooth settings list pairs over LE. Even a classic pairing triggers key derivation (CTKD), followed by `scheduling GATT discovery over LE`, which adds the LE Audio services. That's why a buds reset and re-pair brings the problem back.

My phone's Bluetooth history (`adb shell dumpsys bluetooth_manager`) showed this clearly. From September to early November the pairing record held 12 classic services, no `LE_AUDIO`, and every reconnect logged `AVRCP feature: 1`, even on One UI 9. After the buds were reset and re-paired through the pop-up, the record had 17 services including `LE_AUDIO`, and every reconnect logged `feature: 3`.

## The fix: re-pair with a classic-only record

Pair the buds over classic Bluetooth, and cut the phone's LE service discovery off at the exact moment it starts. The pairing keys are saved by then, but the LE Audio services never get stored. Afterwards:

```
getSsBudsType : <buds>, 0
AbsoluteVolumeDisabled: Absolute volume disabled by property     (no "ignore ... dual mode device")
SetRcFeatures: feature=1
setDeviceVolumeBehavior ... DEVICE_VOLUME_BEHAVIOR_VARIABLE
```

This holds across case close/open reconnects and Bluetooth restarts, with nothing running in the background.

### What you need

- A PC with adb (Android SDK platform-tools), USB debugging on.
- Developer options: **Disable absolute volume** ON, **Bluetooth AVRCP version** 1.3.
- The two files in this folder: `bond.dex` (compiled from `Bond.java`) and `cut_le.sh`.
- Your buds' classic Bluetooth MAC address. Run `adb shell CLASSPATH=/data/local/tmp/bond.dex app_process / Bond info` and take the one listed as `type=DUAL`, or `CLASSIC`.

### Steps

```
adb push bond.dex /data/local/tmp/
adb push cut_le.sh /data/local/tmp/
adb shell sh /data/local/tmp/cut_le.sh AA:BB:CC:DD:EE:FF
```

1. The script removes the existing buds pairings (any bonded device whose name contains "Buds"; pass a different name part as the second argument), restarts Bluetooth and arms the cut.
2. When it says **armed**: go to the home screen and put the buds in pairing mode. Do **not** open Bluetooth settings.
3. If Samsung's pop-up slides up, swipe it away. Do not tap Connect.
4. When the plain **"Pair with …?"** dialog appears, tap **Pair once** and touch nothing else.
5. Airplane mode switches on for a few seconds by itself (that's the cut), then turns off again.
6. Success line: `bond=12 … uuids=12 LE_AUDIO=no`. Close the case lid for 20 s, reopen, and run the proof command: you want `VARIABLE` / `support=false`.

If the result shows `LE_AUDIO=yes`, the cut fired too late. Run the script again.

### Keep it working

- **Never pair the buds again through Samsung's pop-up or the Bluetooth settings list.** That stores LE Audio again and the problem returns. Normal connecting and disconnecting is fine.
- **Don't switch Developer options off with the Settings switch.** Samsung's code (`BluetoothAbsoluteVolumePreferenceController.onDeveloperOptionsSwitchDisabled`) resets `persist.bluetooth.disableabsvol` to false when you do. AVRCP 1.3 survives. If an app such as a bank app needs Developer options hidden, do it from adb instead; the reset never runs that way:
  ```
  adb shell settings put global development_settings_enabled 0
  adb shell settings put global adb_enabled 0
  ```
  Turning Developer options and USB debugging back on by hand later is safe.
- If absolute volume ever comes back with the record still classic-only, toggle **Disable absolute volume** on again in Developer options.

### Trade-offs

- LE Audio features (Auracast, LC3) aren't used with these buds.
- The phone may show the buds as "Personal Device" until their name is read again.

## How the helper works

`Bond.java` runs as the adb shell user through `app_process`; no app install and no root. It uses the shell's Bluetooth privileges for:

- `info`: bonded devices, type, and whether `LE_AUDIO` is in the saved UUIDs.
- `removename <text>` / `remove <address suffix>`: `removeBond`.
- `sdp <mac>`: `fetchUuidsWithSdp(TRANSPORT_BREDR)`, the classic service list only.
- `bond <mac>`: `createBond(TRANSPORT_BREDR)`.

Build it yourself with:

```
javac -source 11 -target 11 -cp <sdk>/platforms/android-36/android.jar -d cls Bond.java
java -cp <sdk>/build-tools/36.0.0/lib/d8.jar com.android.tools.r8.D8 --min-api 34 --lib <sdk>/platforms/android-36/android.jar --output . cls/*.class
mv classes.dex bond.dex
```

Note: the version of `cut_le.sh` in this folder (generic MAC argument, removes pairings by name) is a cleaned-up copy of the script that produced the result above. The logic is the same, but this exact file hasn't been run end to end yet.
