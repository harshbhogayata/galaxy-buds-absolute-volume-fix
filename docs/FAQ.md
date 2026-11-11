# FAQ and troubleshooting

## How do I know which state I'm in?

With USB debugging on:

```
adb shell dumpsys audio | grep -i -E "mAvrcpAbsVolSupported|setDeviceVolumeBehavior|avrcpSupportsAbsoluteVolume"
```

- Working: `DEVICE_VOLUME_BEHAVIOR_VARIABLE`, `support=false`, `mAvrcpAbsVolSupported=false`
- Broken: `DEVICE_VOLUME_BEHAVIOR_ABSOLUTE`, `support=true`

To check the pairing record:

```
adb push tools/bond.dex /data/local/tmp/
adb shell CLASSPATH=/data/local/tmp/bond.dex app_process / Bond info
```

You want your buds' classic address to show `LE_AUDIO=no`.

To see the decision itself, run this while the buds reconnect:

```
adb logcat | grep -E "AbsoluteVolumeDisabled|SetRcFeatures|getSsBudsType"
```

If you see `ignore property because of dual mode device`, the record has LE Audio: re-run the fix. If you see `feature=3` without that line, the "Disable absolute volume" property is off: turn it back on in Developer options.

Without a PC: close the case lid for 20 s, reconnect, and press the phone's volume buttons. If the buds' own volume doesn't follow, it's working.

## Can I use the Galaxy Buds settings app / plugin? It asks me to agree to terms.

Yes. In the working period (September–October) the Buds plugin (`com.samsung.accessory.paranmgr`) connected to the buds on every connection over its own data channel, and the record stayed classic-only. Agreeing to the terms only starts the app.

Avoid these inside it:

- **Forget** / re-pair. Re-pairing through the pop-up or the Settings list stores LE Audio again.
- Any option that turns **LE Audio** on. The plugin has a message that says you can turn LE Audio on in its settings.

UHQ audio is safe to turn on.

## Samsung's pop-up appeared asking to connect. What do I do?

Close it. If the buds are already paired you don't need it, and accepting it can start a new pairing.

## A bank app complains about Developer options.

Don't turn them off with the Settings switch: that resets "Disable absolute volume" to false. Hide them from adb instead:

```
adb shell settings put global development_settings_enabled 0
adb shell settings put global adb_enabled 0
```

The second command ends adb, so run it last. Turning Developer options and USB debugging back on by hand later is safe; re-enabling doesn't reset anything.

If they did get switched off with the switch: re-enable them and toggle **Disable absolute volume** on. No re-pairing needed.

## Does it survive a reboot / OTA update?

The September–October record survived reboots and a One UI 9 update, so the record itself is durable. An OTA could reset Developer options. After any update, check that **Disable absolute volume** is on and **AVRCP** is 1.3.

## What do I lose?

LE Audio for these buds (LC3, Auracast broadcast). Classic SSC/UHQ audio works as normal.

## The buds show up as "Personal Device".

The name wasn't read before the cut. It updates on a later connection, or you can rename the buds in Bluetooth settings.

## The fix script printed `LE_AUDIO=yes`.

The cut fired too late, or something scanned first. Run it again from the home screen, and don't open Bluetooth settings while it runs.

## The fix script never pairs (`bond failed`).

- Make sure the buds are in pairing mode.
- Tap **Pair** on the plain dialog exactly once.
- Don't tap the Samsung pop-up's Connect.
- If you see `isPackageNameAccurate` in logcat, you're running an old `bond.dex`.

## My buds are now unpaired after a failed run.

Run the script again. It removes and re-creates the pairing each time.

## Does this help with distortion?

It removes absolute volume, which is what breaks EQ apps' direct volume control. If you stack two EQ apps (for example Poweramp Equalizer + Wavelet), give each enough negative preamp, or you can still get digital clipping.
