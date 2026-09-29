#!/usr/bin/env bash
# Checks scripts/bench.sh against a fake adb (no device, no Gradle): it must exit 0 once the suite
# logs "done", poll logcat with the one filterspec that shows Info lines, restore the exact
# stay_on_while_plugged_in value it found (a number, or unset), and restore it on a failed run too.
#
#   scripts/bench-test.sh        prints "bench-test: OK" or the first failed check
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fail() { echo "bench-test: FAIL $1" >&2; [ -f "$work/out" ] && sed 's/^/  | /' "$work/out" >&2; exit 1; }

# Logcat lines for one warm-up run and one measured run.
python3 - "$work/lines" <<'EOF'
import sys
out = ["I ChessBench: start runs=1 positions=6 depth=14 wallMs=3000 maxMemoryMb=128 processors=8"]
for run in (0, 1):
    for pos in ("start", "kiwipete", "ruylopez", "endgame", "position4", "position6"):
        for kind in ("depth limit=14", "wall limit=3000"):
            out.append(f"I ChessBench: search run={run} warmup={str(run == 0).lower()} pos={pos} kind={kind} "
                       f"depth=14 nodes=300000 ms=2000 nps=150000 cpu=7 thread=chess-engine tid=4242 "
                       f"move=e2e4 ttd=1:1,2:3,14:2000")
out.append("I ChessBench: done usedMemoryMb=40")
open(sys.argv[1], "w").write("\n".join(out) + "\n")
EOF

cat >"$work/adb" <<'EOF'
#!/usr/bin/env bash
# Fake adb: `-s <serial> <command...>`; state in $FAKE_DIR.
shift 2
echo "$*" >>"$FAKE_DIR/calls"
case "$*" in
  get-state) echo device ;;
  "shell settings get global stay_on_while_plugged_in") cat "$FAKE_DIR/stay_on" ;;
  "shell settings put global stay_on_while_plugged_in "*) echo "$6" >"$FAKE_DIR/stay_on" ;;
  "shell settings delete global stay_on_while_plugged_in") echo null >"$FAKE_DIR/stay_on" ;;
  "shell cmd package resolve-activity"*) echo com.yarosz.chess/com.thelightphone.sdk.Main ;;
  "shell dumpsys thermalservice") printf 'IsStatusOverride: false\nThermal Status: 0\n' ;;
  "shell getprop ro.product.model") echo TLP301 ;;
  "logcat -d "*)
    # Like logcat: a later filterspec for the same tag replaces an earlier one.
    spec=${@: -1}
    if [ "$spec" = "ChessBench:I" ]; then cat "$FAKE_DIR/lines"; else grep '^E ' "$FAKE_DIR/lines" || true; fi
    [ -f "$FAKE_DIR/fail" ] && echo "E ChessBench: failed boom"
    ;;
esac
exit 0
EOF
chmod +x "$work/adb"
touch "$work/apk"

run() { # <initial stay_on> <expected exit status>
  echo "$1" >"$work/stay_on"
  : >"$work/calls"
  set +e
  FAKE_DIR=$work BENCH_ADB=$work/adb BENCH_APK=$work/apk scripts/bench.sh -n 1 FAKE123 >"$work/out" 2>&1
  status=$?
  set -e
  [ "$status" = "$2" ] || fail "stay_on=$1: exit $status, expected $2"
  [ "$(cat "$work/stay_on")" = "$1" ] || fail "stay_on=$1: left at $(cat "$work/stay_on")"
  grep -qx 'shell settings put global stay_on_while_plugged_in 7' "$work/calls" || fail "stay_on=$1: never set to 7"
}

run 2 0
grep -qx 'logcat -d -s ChessBench:I' "$work/calls" || fail "logcat is not polled with -s ChessBench:I alone"
grep -q 'searching thread (searches): chess-engine tid 4242: 12' "$work/out" || fail "report lacks the searching thread"
grep -q 'restored to 2' "$work/out" || fail "no restore message"
run null 0
grep -qx 'shell settings delete global stay_on_while_plugged_in' "$work/calls" || fail "an unset value is not deleted again"
run 0 0
touch "$work/fail"
run 3 1
grep -q 'the suite failed on the device' "$work/out" || fail "a failed suite is not reported"
rm "$work/fail"

echo "bench-test: OK"
