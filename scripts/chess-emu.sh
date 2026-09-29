#!/usr/bin/env bash
# adb, aimed at the Chess emulator only. The emulator is found by AVD name (CHESS_AVD, default
# LightPhone3-chess), never by position in `adb devices`: other Tools run their own emulators on this
# machine (the Reader's LightPhone3 is emulator-5554), and a script that took the first one would drive
# the wrong Tool. Fails when no emulator, or more than one, runs that AVD.
#
#   scripts/chess-emu.sh serial          print the serial (e.g. emulator-5556)
#   scripts/chess-emu.sh <adb args...>   run adb -s <serial> <adb args...>
set -uo pipefail
adb="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
avd=${CHESS_AVD:-LightPhone3-chess}

serial=$("$adb" devices | awk '/^emulator-[0-9]+\tdevice/{print $1}' | while read -r s; do
  [ "$("$adb" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')" = "$avd" ] && echo "$s"; done)
if [ -z "$serial" ]; then
  echo "chess-emu: no emulator runs AVD $avd (mise run emu, or scripts/emulator/RECIPE.md)" >&2
  exit 1
fi
if [ "$(wc -l <<<"$serial")" -ne 1 ]; then
  echo "chess-emu: several emulators run AVD $avd ($(echo $serial)); stop the extra one" >&2
  exit 1
fi

if [ "${1:-}" = serial ]; then echo "$serial"; exit 0; fi
exec "$adb" -s "$serial" "$@"
