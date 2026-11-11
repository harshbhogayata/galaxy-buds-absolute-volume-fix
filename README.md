# Galaxy Buds absolute volume on One UI 9: root cause and non-root fix

**Symptom:** "Disable absolute volume" and AVRCP 1.3 are on, the first connection after pairing has separate phone and buds volume, and every reconnect after that is synced (absolute) volume again. Apps that control volume themselves (Poweramp Equalizer DVC, Wavelet) sound wrong.

**Cause:** Samsung's Bluetooth stack treats buds whose saved pairing record lists the `LE_AUDIO` service as "Samsung dual buds". For those it forces LE Audio on at every connect, and its AVRCP code reads "Disable absolute volume" and **ignores it on purpose**:

```
AbsoluteVolumeDisabled: Absolute volume disabled by property
AbsoluteVolumeDisabled: ignore property because of dual mode device
SetRcFeatures: feature=3
```

Pairing through Samsung's pop-up or the Bluetooth settings list always stores `LE_AUDIO`. That's why resetting and re-pairing the buds brings the problem back.

**Fix (no root):** re-pair the buds over classic Bluetooth and cut the phone's LE service discovery off at the exact moment it starts, so the record keeps only classic services. After that every reconnect gives `feature=1` and `DEVICE_VOLUME_BEHAVIOR_VARIABLE`, with nothing running in the background.

Tested on: Galaxy S24 SM-S921B (Exynos), One UI 9 / Android 17, build S921BXXSIEZJ1, Galaxy Buds3 Pro. It likely applies to other One UI 8.5–9 phones and other LE Audio Galaxy Buds (Buds2 Pro, Buds3), but those weren't tested; reports welcome.

## Docs

| | |
|---|---|
| [docs/TIMELINE.md](docs/TIMELINE.md) | The whole story: August fix, why it held for months, what broke it on 9 Nov, how the cause was found |
| [docs/ROOT-CAUSE.md](docs/ROOT-CAUSE.md) | The exact code paths in Samsung's Bluetooth module and Settings, with method names |
| [docs/WHAT-DIDNT-WORK.md](docs/WHAT-DIDNT-WORK.md) | Every setting, command and workaround tried, and why each fails on One UI 9 |
| [docs/FAQ.md](docs/FAQ.md) | Checking your state, the Buds app, bank apps and Developer options, troubleshooting |
| [evidence/bluetooth-history.md](evidence/bluetooth-history.md) | Three months of the phone's Bluetooth history: working vs broken records |
| [evidence/connection-logs.md](evidence/connection-logs.md) | Logcat of broken vs fixed connections, and why each pairing path adds LE Audio |

## The fix

### You need

- A PC with adb (Android SDK platform-tools) and USB debugging on.
- Developer options: **Disable absolute volume** ON, **Bluetooth AVRCP version** = AVRCP 1.3.
- From this repo: `tools/bond.dex` and `tools/cut_le.sh`.
- Your buds' classic Bluetooth address:
  ```
  adb push tools/bond.dex /data/local/tmp/
  adb shell CLASSPATH=/data/local/tmp/bond.dex app_process / Bond info
  ```
  Use the address shown as `type=DUAL` or `CLASSIC`, not the `type=LE` one.

### Run it

```
adb push tools/bond.dex /data/local/tmp/
adb push tools/cut_le.sh /data/local/tmp/
adb shell sh /data/local/tmp/cut_le.sh AA:BB:CC:DD:EE:FF
```

1. The script removes existing pairings whose name contains "Buds" (pass another name part as a second argument), restarts Bluetooth and arms the cut.
2. When it prints **armed**: go to the home screen and put the buds in pairing mode. Don't open Bluetooth settings.
3. If Samsung's pop-up appears, swipe it away. Don't tap Connect.
4. When the plain **"Pair with …?"** dialog appears, tap **Pair** once and touch nothing else.
5. Airplane mode turns on for a few seconds by itself (that's the cut), then off again.
6. Success: `bond=12 … uuids=12 LE_AUDIO=no`. Close the case lid for 20 s, reopen it, then check:
   ```
   adb shell dumpsys audio | grep -i -E "mAvrcpAbsVolSupported|setDeviceVolumeBehavior|avrcpSupportsAbsoluteVolume"
   ```
   You want `VARIABLE` and `support=false`.

If it prints `LE_AUDIO=yes`, run it again.

### Keep it working

- **Never re-pair the buds through Samsung's pop-up or the Bluetooth settings list.** Normal connecting and disconnecting is fine. Using the Buds settings app is fine, but don't use Forget or any LE Audio option in it.
- **Don't switch Developer options off with the Settings switch.** That resets "Disable absolute volume" to false (AVRCP 1.3 survives). To hide them for bank apps, use:
  ```
  adb shell settings put global development_settings_enabled 0
  adb shell settings put global adb_enabled 0
  ```
  Turning them back on by hand later is safe.
- If absolute volume comes back and `Bond info` still shows `LE_AUDIO=no`, toggle **Disable absolute volume** on again.

You give up LE Audio (LC3/Auracast) for these buds.

## Tools

- `tools/Bond.java`, prebuilt as `tools/bond.dex`: runs as the adb shell user via `app_process` (no app install, no root). Modes:
  - `info`: bonded devices, their type, and whether `LE_AUDIO` is saved
  - `remove <address suffix>…` / `removename <text>`
  - `sdp <mac>`: classic-only service fetch
  - `bond <mac>`: `createBond(TRANSPORT_BREDR)`
- `tools/cut_le.sh`: the re-pair script.
- `tools/legacy-watcher/`: the earlier bandage approach (re-applying VARIABLE volume after every connect). Kept for reference; not needed with the fix.

Build `bond.dex` yourself:

```
javac -source 11 -target 11 -cp <sdk>/platforms/android-36/android.jar -d cls tools/Bond.java
java -cp <sdk>/build-tools/36.0.0/lib/d8.jar com.android.tools.r8.D8 --min-api 34 --lib <sdk>/platforms/android-36/android.jar --output . cls/*.class
mv classes.dex tools/bond.dex
```

**Status:** the method was confirmed on the phone above. The script in this repo is a cleaned-up copy of the one that produced that result (MAC as an argument, removes pairings by name). The logic is the same, but this exact file hasn't been run end to end yet.

## License

MIT
