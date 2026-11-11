# Legacy watcher (bandage, not needed with the fix)

These helpers were the first workaround, before the root cause was known. They run through `app_process` as the adb shell user.

- `LeaPolicy.java`
  - `list`: shows the LE Audio connection policy of bonded devices
  - `set <policy> <suffixes>`: sets that policy
  - `watch <main suffix> <other suffix>`: every 3 s, forbids LE Audio, forces `setDeviceVolumeBehavior(VARIABLE)` on the main bud, and turns Samsung's per-device volume control off
- `Avc.java`: Samsung's `setAbsoluteVolumeControl` (global, ignored on One UI 9), `setVolumeControlSupport` (per device), and `semSetAudioType`

Limitations:

- Samsung resets all of these at every connect, so the watcher has to keep running (about 150 MB of memory), and it stops on reboot.
- The buds' touch-volume gesture still moves the phone volume.

Use the classic-only re-pair in the main README instead.

Build them the same way as `Bond.java` (`javac` against `android.jar`, then `d8`).
