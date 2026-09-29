#!/usr/bin/env bash
# The engine benchmark on a device (decision E11: v2 PR 2's go/no-go gate for Pirarucu). Builds and
# installs the benchmark Tool (the `benchmark` build type: not debuggable, not minified, dev-signed),
# asks it to run the fixed search suite (tool/src/benchmark/.../bench/BenchSuite.kt) on its engine
# thread, waits for the ChessBench logcat lines and prints P50/P90 of the time to depth, nodes per
# second and depth reached, the CPU cores and thread id of the searching thread, the heap limit, and the
# thermal status before and after.
#
#   scripts/bench.sh [-n runs] [-d] <adb serial>        (mise run bench <serial>)
#
#   -d  build the same APK debuggable (-Pbench.debuggable=true), to compare with the default build
#
# The serial is required: the benchmark never picks a device by itself. An emulator-* serial gets the
# emulator build (scripts/emulator-build.sh), but only the phone's numbers mean anything. On the shared
# Light Phone III, hold the phone lease (umbrella PLATFORM.md) for the whole run.
#
# Trigger: `run-as` is closed on a non-debuggable build, and a file adb puts under
# /sdcard/Android/data/<package> belongs to the shell, which the Tool cannot read. So each run builds the
# APK with a fresh id and the run count (-Pbench.id, -Pbench.runs); the Tool runs the suite once per id
# (BenchEntryPoint). The script changes one setting, stay_on_while_plugged_in (7 during the run), and
# restores the exact value it read, also on Ctrl-C. Each run is 6 positions x (depth 14 + 3 s); run 0
# warms up and is left out.
#
# For scripts/bench-test.sh only: BENCH_ADB replaces adb and BENCH_APK a prebuilt APK (no Gradle).
set -euo pipefail

runs=5
debuggable=false
usage="usage: scripts/bench.sh [-n runs] [-d] <adb serial>"
while getopts "n:d" opt; do
  case "$opt" in
    n) runs=$OPTARG ;;
    d) debuggable=true ;;
    *) echo "$usage" >&2; exit 2 ;;
  esac
done
shift $((OPTIND - 1))
[ $# -eq 1 ] && [ -n "$1" ] || { echo "$usage" >&2; exit 2; }
serial=$1
die() { echo "bench: $1" >&2; exit 1; }
[[ $runs =~ ^[1-9][0-9]?$ ]] && [ "$runs" -le 50 ] || die "-n wants 1-50 runs, got '$runs'"

cd "$(git rev-parse --show-toplevel)"
adb="${BENCH_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
pkg=com.yarosz.chess
a() { "$adb" -s "$serial" "$@"; }

a get-state >/dev/null 2>&1 || die "device $serial is not attached"
apk=${BENCH_APK:-tool/build/outputs/apk/benchmark/tool-benchmark.apk}
if [ -z "${BENCH_APK:-}" ]; then
  build=(mise exec -- ./gradlew -q --console=plain :tool:assembleBenchmark "-Pbench.debuggable=$debuggable"
    "-Pbench.runs=$runs" "-Pbench.id=$(date +%Y%m%d%H%M%S)-$$")
  case "$serial" in
    emulator-*) scripts/emulator-build.sh "${build[@]}" ;;
    *) "${build[@]}" ;;
  esac
fi
[ -f "$apk" ] || die "no APK at $apk"

# The LP3 drops off USB while asleep: wake it, wait up to 30 s for adb, clear a PIN-less lock screen.
for _ in $(seq 1 15); do
  if a shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; then break; fi
  sleep 2
done
a shell wm dismiss-keyguard >/dev/null 2>&1 || die "device not reachable over adb"

# Read the setting before touching anything; refuse to run if it can't be read back exactly.
stay_on=$(a shell settings get global stay_on_while_plugged_in | tr -d '\r')
[[ $stay_on =~ ^([0-9]+|null)$ ]] || die "cannot read stay_on_while_plugged_in (got '$stay_on'); nothing changed"
echo "bench: stay_on_while_plugged_in was $stay_on"

work=$(mktemp -d)
restore() {
  trap - EXIT INT TERM HUP
  {
    if [ "$stay_on" = null ]; then
      a shell settings delete global stay_on_while_plugged_in >/dev/null || true
    else
      a shell settings put global stay_on_while_plugged_in "$stay_on" || true
    fi
  } 2>/dev/null
  now=$(a shell settings get global stay_on_while_plugged_in 2>/dev/null | tr -d '\r' || true)
  if [ "$now" = "$stay_on" ]; then
    echo "bench: stay_on_while_plugged_in restored to $stay_on"
  else
    echo "bench: WARNING stay_on_while_plugged_in is '$now', expected '$stay_on'" >&2
  fi
  rm -rf "$work"
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

a install -r "$apk" >/dev/null
activity=$(a shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg | tail -1 | tr -d '\r')
thermal() { a shell dumpsys thermalservice | tr -d '\r' | grep -m1 'Thermal Status' | sed 's/^ *//'; }

a shell settings put global stay_on_while_plugged_in 7
a shell am force-stop $pkg
a logcat -c
echo "bench: thermal before: $(thermal)"
a shell am start -W -n "$activity" >/dev/null
echo "bench: running $runs runs (+1 warm-up) of $apk (debuggable=$debuggable) on $(a shell getprop ro.product.model | tr -d '\r')"

# About 6 x (depth 14 + 3 s) per run; allow 60 s per run on a slow phone, plus a minute.
deadline=$(( $(date +%s) + 60 * (runs + 1) + 60 ))
lines=$work/lines
seen=0
while :; do
  # One filterspec: `-s ChessBench:I` shows Info and above (errors included). A second
  # `ChessBench:E` would replace the first and hide every Info line.
  a logcat -d -s ChessBench:I >"$lines" || true
  grep -q 'ChessBench: failed' "$lines" && { cat "$lines" >&2; die "the suite failed on the device"; }
  grep -q 'ChessBench: done' "$lines" && break
  count=$(grep -c 'ChessBench: search ' "$lines" || true)
  if [ "$count" != "$seen" ]; then seen=$count; echo "bench: $count/$(( (runs + 1) * 12 )) searches"; fi
  [ "$(date +%s)" -lt "$deadline" ] || die "no 'done' line before the deadline ($seen searches logged)"
  sleep 5
done
echo "bench: thermal after: $(thermal)"

echo
python3 scripts/bench-report.py "ChessBench on $(a shell getprop ro.product.model | tr -d '\r') (debuggable=$debuggable)" <"$lines"
exit 0
