# Timeline: how this was found

One phone (Galaxy S24 SM-S921B, Exynos) and one pair of Galaxy Buds3 Pro, used with Poweramp Equalizer and Wavelet. The goal throughout: phone volume and buds volume kept separate (no Bluetooth absolute volume), so the EQ apps' volume control doesn't distort.

## August: One UI 8.5

**1 Aug: a side issue first.** The Buds plugin `com.samsung.accessory.budsunitemgr` had been removed with `pm uninstall --user 0`. After that, Bluetooth settings crashed trying to open `budsunitemgr/.WelcomeActivity`, and the buds settings page was gone. It was restored with:

```
adb shell cmd package install-existing --user 0 com.samsung.accessory.budsunitemgr
adb shell pm enable --user 0 com.samsung.accessory.budsunitemgr
```

That was unrelated to volume. Don't uninstall the Buds plugins.

**2 Aug: the original problem.** "Disable absolute volume" did nothing. Resetting Wi-Fi and Bluetooth settings and pairing with the case button gave separate volume once, and the next reconnect was synced again. Tried and failed that day (details in [WHAT-DIDNT-WORK.md](WHAT-DIDNT-WORK.md)):

- `settings put global bluetooth_disable_abs_volume 1`
- `bluetooth_le_audio_enabled 0` in the system and global settings tables
- `bluetooth_disabled_profiles 12582912` (LE Audio and LE Volume Control)
- `bluetooth_interoperability_list "C4:77:64,2"`
- `setprop persist.bluetooth.disableabsvol true`: permission denied
- `aflags`: needs root

**What worked:** Developer options → **Bluetooth AVRCP version → 1.3**, **Disable absolute volume ON**, restart, forget only the buds, pair again. `dumpsys audio` then showed `DEVICE_VOLUME_BEHAVIOR_VARIABLE` / `support=false` on every reconnect.

The pairing record created that day ended up with only classic services (12, no `LE_AUDIO`). Looking back, that's the real reason it held. The exact step that dropped the LE services isn't recorded; it's possibly the `bluetooth_disabled_profiles` experiment, which One UI 8.5 still read.

**9 Aug:** some distortion remained. It was put down to the two EQ apps stacking, not to Bluetooth.

## September – 3 November: working

Bluetooth history (`dumpsys bluetooth_manager`) shows every reconnect with codec `SSC UHQ2` (UHQ audio on) and `AVRCP feature: 1`. That includes connections after the phone updated to **One UI 9** (build `S921BXXUHEZIF`, around 20 Oct). See [../evidence/bluetooth-history.md](../evidence/bluetooth-history.md).

## 9 November: broke

- A new One UI 9 build (`S921BXXSIEZJ1`) was installed.
- The buds were reset by accident and paired again through Samsung's pop-up (`semCreateBond` from `com.samsung.android.easysetup`).
- The new record had 17 services including `LE_AUDIO`. From then on: `connect by dual mode updated`, `feature: 3`, synced volume from the second connection onward.

## 10 November: workarounds (partial)

- Re-applying AVRCP 1.3, toggling the disable switch, rebooting, re-pairing: first connection fine, second synced.
- Poweramp's published adb binder command (`service call audio …`) is rejected on One UI 9 (parcel format changed).
- A shell helper (`app_process`, see `tools/legacy-watcher/`) could, while connected:
  - set the buds' LE Audio connection policy to FORBIDDEN
  - call `AudioManager.setDeviceVolumeBehavior(VARIABLE)`
  - call Samsung's `BluetoothDevice.setVolumeControlSupport(false)`
- Samsung reset all three on every reconnect, so a 3-second watcher loop re-applied them. Poweramp then reported "Absolute Volume → Disabled", but the buds' touch gesture still moved the phone volume, and the sound still wasn't right. Not a real fix.

## 11 November: root cause and fix

1. The month of Bluetooth history in `dumpsys bluetooth_manager` showed the working period was classic-only with `feature: 1` even on One UI 9. The break lined up exactly with the 9 Nov re-pair.
2. The live log pointed at the culprit: `Absolute volume disabled by property` immediately followed by `ignore property because of dual mode device`.
3. Decompiling the phone's own Bluetooth module traced "dual mode device" to `mSsBudsType == 1`, which is set only when the saved services include `LE_AUDIO`. See [ROOT-CAUSE.md](ROOT-CAUSE.md).
4. UHQ audio on: dual mode is reported "disabled", but the type stays 1 and absolute volume stays on. Not enough.
5. Classic-only pairing attempts:
   - `createBond(TRANSPORT_BREDR)` first failed with `isPackageNameAccurate` (fixed by setting the shell attribution source).
   - Pairing from the Settings list goes over LE.
   - A clean classic pairing still ran key derivation, then LE discovery, and stored `LE_AUDIO`.
   - Cutting Bluetooth at key-derivation start aborted the pairing.
   - The pairing dialog plus auto-confirm caused `cancelBondProcess`, or a CTKD timeout.
   - Cutting with `cmd bluetooth_manager disable` left LE running (`BLE ON`), so discovery finished anyway.
6. **Working method:** prefetch the classic service list, `createBond(TRANSPORT_BREDR)`, the user taps Pair once, and **airplane mode** goes on at `scheduling GATT discovery over LE`. Result: paired, 12 services, `LE_AUDIO=no`, type 0, `feature=1`, `VARIABLE`. Confirmed across a lid-close reconnect and Bluetooth restarts, with nothing running in the background.
7. **Developer options:** switching them off in Settings resets `persist.bluetooth.disableabsvol` to false. Hiding them with `settings put global development_settings_enabled 0` (and `adb_enabled 0`) keeps the fix. Verified on a reconnect.
