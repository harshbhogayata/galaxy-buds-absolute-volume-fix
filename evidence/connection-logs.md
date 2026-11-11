# Connection logs: broken vs fixed (logcat, sanitized)

Phone: Galaxy S24 (SM-S921B), One UI 9, build S921BXXSIEZJ1. Buds3 Pro. `<buds-A>` is the main bud's classic address.

## Broken: record contains LE_AUDIO (after re-pairing through Samsung's pop-up)

Bluetooth start. The bud is classified as "dual buds" even before it connects:

```
I BluetoothRemoteDevices: getSsBudsType : <buds-A>, 1
I LeAudioStateMachine: updateSsDualBuds : true (<buds-A>)
I bluetooth: system/bta/le_audio/client.cc:1520 SetSsDualBuds:  address : <buds-A>, value: true
```

Classic audio connects and Samsung re-enables LE Audio on its own (`uid 1002` is the Bluetooth app):

```
A2DPSVC-connectLeAudio
LEAS-setConnectionPolicy <buds-A>, 100, uid/pid=1002/...
LEAS-connectSet : groupId 1
```

AVRCP reads "Disable absolute volume" and then ignores it:

```
W avrcp : system/profile/avrcp/connection_handler.cc:95 AbsoluteVolumeDisabled: Absolute volume disabled by property
W avrcp : system/profile/avrcp/connection_handler.cc:98 AbsoluteVolumeDisabled: ignore property because of dual mode device
I avrcp : system/profile/avrcp/device.cc:654 SetRcFeatures: feature=3
I AS.AudioService: setDeviceVolumeBehavior: dev:bt_a2dp addr:<buds-A> behavior:DEVICE_VOLUME_BEHAVIOR_ABSOLUTE pack:com.android.bluetooth
I AS.AudioService: avrcpSupportsAbsoluteVolume addr=<buds-A> support=true
```

The same happens with UHQ audio on (codec SSC 96 kHz, `Dual mode enable state is: false`), and with the LE Audio policy forced to 0 before a Bluetooth restart. The policy goes straight back to 100.

## Fixed: classic-only record (12 services, no LE_AUDIO)

```
I BluetoothRemoteDevices: getSsBudsType : <buds-A>, 0
I BluetoothUtils: Dual mode enable state is: false
I AS.AudioService: setDeviceVolumeBehavior: dev:bt_a2dp addr:<buds-A> behavior:DEVICE_VOLUME_BEHAVIOR_VARIABLE pack:com.android.bluetooth
I AS.AudioService: avrcpSupportsAbsoluteVolume addr=<buds-A> support=false
W avrcp : system/profile/avrcp/connection_handler.cc:95 AbsoluteVolumeDisabled: Absolute volume disabled by property
I avrcp : system/profile/avrcp/device.cc:654 SetRcFeatures: feature=1
```

This holds across a case-lid close/open reconnect (`ACL_DISCONNECTED reason 19` → reconnect 34 s later → `feature: 1`), across Bluetooth restarts, and with Developer options hidden via `settings put global development_settings_enabled 0`.

There's no `updateSsDualBuds`, no `connectLeAudio`, and nothing running in the background.

## Why the plain "pair from Settings" path always adds LE Audio

Even a classic `createBond(TRANSPORT_BREDR)` ends with:

```
I bt_bta_gattc: system/stack/btm/btm_sec.cc:3315 btm_sec_perform_ctkd: Started SMP over BR/EDR <buds-A>
W smp_act : smp_br_process_link_key: LTK derivation from LK successfully completed
I bt_btif_dm: btif_dm.cc:4376 btif_dm_ble_auth_cmpl_evt: scheduling GATT discovery over LE for <buds-A>
W bluetooth: le_impl.h create_le_connection: device=<buds-A>, direct=true
BluetoothRemoteDevices -- AbstractionLayer.BT_PROPERTY_UUIDS, device is <buds-A>, BATTERY,VolumeControl,CoordinatedSet,LE_AUDIO,BASS
BluetoothRemoteDevices-change SsBudsType <buds-A> to 1 from BT_PROPERTY_UUIDS
```

Pairing from the Settings list or the pop-up goes over LE directly (`LE Enhanced Connection` → SMP → `Link key is derived from LTK`), with the same result.

## The cut

The fix script watches for `scheduling GATT discovery over LE` and switches airplane mode on. In a test, `cmd bluetooth_manager disable` only dropped the adapter to `BLE ON`: the LE connection completed 30 ms later and discovery still stored `LE_AUDIO`. Airplane mode goes all the way to `BT OFF`:

```
BluetoothAdapterState Entering Turning BT OFF
BluetoothAdapterState Entering BLE ON
BluetoothAdapterState Entering Turning BLE OFF
BluetoothAdapterState Entering BT OFF
```

A successful run of the script printed:

```
prefetch 1: <buds-A> type=DUAL bond=10 uuids=12 LE_AUDIO=no
CUT: airplane mode on before LE discovery
bond 1: createBond(BR/EDR) <buds-A> -> true BONDED
<buds-A> type=DUAL bond=12 uuids=12 LE_AUDIO=no
```
