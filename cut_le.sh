#!/system/bin/sh
# Re-pair Samsung Galaxy Buds over classic Bluetooth only, so the phone never saves their
# LE Audio services. Without LE_AUDIO in the saved record, One UI stops treating the buds as
# "dual buds", stops forcing LE Audio on, and honours "Disable absolute volume" again.
#
# Usage (from a PC):  adb push bond.dex cut_le.sh /data/local/tmp/
#                     adb shell sh /data/local/tmp/cut_le.sh <BUDS_MAC> [name part, default "Buds"]
# Then put the buds in pairing mode, close Samsung's pop-up, tap Pair once on the plain dialog.
# Progress and result: adb shell cat /data/local/tmp/cut_le.log
MAC=$(echo "$1" | tr a-z A-Z)
NAME=${2:-Buds}
[ -z "$MAC" ] && { echo "usage: sh cut_le.sh <BUDS_MAC> [name part]"; exit 1; }
T=/data/local/tmp
export CLASSPATH=$T/bond.dex
B="app_process / Bond"
log() { echo "$(date +%T.%N | cut -c1-12) $*" | tee -a $T/cut_le.log; }
: > $T/cut_le.log

log "removing existing pairings named *$NAME*"
$B removename "$NAME" 2>&1 | grep -v -i warn | tee -a $T/cut_le.log
cmd bluetooth_manager disable >/dev/null; sleep 4; cmd bluetooth_manager enable >/dev/null; sleep 4
logcat -c

# The cut: as soon as the stack schedules LE GATT discovery (keys are saved by then), switch
# airplane mode on. A plain Bluetooth disable leaves LE running ("BLE ON") and discovery completes.
( logcat -b all -e 'scheduling GATT discovery over LE' -m 1 > $T/cut_line.txt 2>&1
  cmd connectivity airplane-mode enable
  log "CUT: airplane mode on before LE discovery" ) &
WATCH=$!
log "armed: put the buds in pairing mode now, tap Pair once"

i=0
while [ $i -lt 12 ]; do
  i=$((i+1))
  pre=$(app_process -Dwait=3000 / Bond sdp "$MAC" 2>&1 | grep -v -i warn | tail -1)
  log "prefetch $i: $pre"
  grep -q CUT $T/cut_le.log && break
  out=$($B bond "$MAC" 2>&1 | grep -v -i warn | tr '\n' ' ')
  log "bond $i: $out"
  grep -q CUT $T/cut_le.log && break
  case "$out" in *BONDED*) break;; esac
  sleep 1
done
sleep 3
kill $WATCH 2>/dev/null
cmd connectivity airplane-mode disable; sleep 4; cmd bluetooth_manager enable >/dev/null; sleep 8
app_process -Dwait=8000 / Bond sdp "$MAC" 2>&1 | grep -v -i warn | tee -a $T/cut_le.log
$B info 2>&1 | grep -v -i warn | tee -a $T/cut_le.log
log "done. Success = bond=12 and LE_AUDIO=no for $MAC"
