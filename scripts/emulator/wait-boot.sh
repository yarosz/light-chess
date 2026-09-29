#!/usr/bin/env bash
# Wait until emulator-5556 reports sys.boot_completed=1 (max ~5 min). Optional arg "reboot" reboots first.
a="$HOME/Library/Android/sdk/platform-tools/adb"
s=emulator-5556
if [ "${1:-}" = reboot ]; then "$a" -s $s reboot; sleep 10; fi
"$a" -s $s wait-for-device
for i in $(seq 1 60); do
  [ "$("$a" -s $s shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo booted; sleep 10; exit 0; }
  sleep 5
done
echo "boot timed out" >&2; exit 1
