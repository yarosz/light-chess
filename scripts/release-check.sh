#!/usr/bin/env bash
# The release checks of RELEASING.md that a script can run (decision log D9 item 5).
#
#   scripts/release-check.sh apk            unsigned minified release, as Light builds it: size, badging
#   scripts/release-check.sh relay          a release that declares INTERNET must have a Relay URL (W8)
#   scripts/release-check.sh run            minified release on the Chess emulator: open, solve a Puzzle, About
#   scripts/release-check.sh upgrade REF    REF's build with two solved Puzzles, then this release over it
#   scripts/release-check.sh scan           public-content scan of the tracked files and of all history
#   scripts/release-check.sh all REF        all five
#
# `run` and `upgrade` uninstall the Tool from the Chess emulator (AVD $CHESS_AVD, found by name through
# scripts/chess-emu.sh) and drive it with scripts/release-drive.py. Screenshots and JSON go to
# $RELEASE_CHECK_OUT (default build/release-check). `scan` also reads extra patterns, one extended
# regex per line, from the file named by $RELEASE_SCAN_PRIVATE: names, serials and hostnames that must
# never be committed, kept outside the repo because listing them here would publish them.
#
# Each check prints "release-check: <check> OK ..." or fails with "release-check: FAIL ...".
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
cd "$repo"
pkg=com.yarosz.chess
out=${RELEASE_CHECK_OUT:-build/release-check}
mkdir -p "$out"
android=${ANDROID_HOME:-$HOME/Library/Android/sdk}
aapt=$(ls -d "$android"/build-tools/*/ | sort -V | tail -1)aapt
drive() { python3 scripts/release-drive.py "$@"; }
emu() { scripts/chess-emu.sh "$@"; }
die() { echo "release-check: FAIL $*" >&2; exit 1; }
gradle=(./gradlew -q --console=plain)
emulator_apk=tool/build/outputs/apk/release/tool-release.apk

check_apk() {
  "${gradle[@]}" :tool:assembleRelease -DlightSdk.unsigned=true
  local apk=tool/build/outputs/apk/release/tool-release-unsigned.apk badging
  [ -f "$apk" ] || die "no unsigned release APK"
  badging=$("$aapt" dump badging "$apk")
  grep -q "^package: name='$pkg' versionCode='[0-9]*' versionName='[0-9.]*'" <<<"$badging" \
    || die "badging: $(head -1 <<<"$badging")"
  # lighttool.toml declares INTERNET, for the Relay only (ADR 0004, ToolMetadataTest); the manifest merger
  # adds those of Light's SDK and its libraries (OkHttp and Google's datatransport add INTERNET too). Both
  # sets are pinned here, so that any new permission fails the check.
  local declared sdk_permissions="android.permission.ACCESS_NETWORK_STATE android.permission.CAMERA
android.permission.FOREGROUND_SERVICE android.permission.INTERNET android.permission.RECEIVE_BOOT_COMPLETED
android.permission.VIBRATE android.permission.WAKE_LOCK $pkg.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
  declared=$(declared_permissions)
  [ "$declared" = "android.permission.INTERNET" ] || die "lighttool.toml declares \"$declared\", pinned: android.permission.INTERNET"
  local found extra
  found=$(grep "^uses-permission:" <<<"$badging" | sed -E "s/.*name='([^']+)'.*/\1/" | sort)
  extra=$(comm -23 <(echo "$found") <(tr ' ' '\n' <<<"$sdk_permissions" | grep . | sort))
  [ -z "$extra" ] || die "permissions beyond the SDK's pinned set: $(echo $extra)"
  echo "release-check: apk OK $(head -1 <<<"$badging" | grep -oE "versionCode='[0-9]+' versionName='[^']+'")," \
    "$(stat -f%z "$apk" 2>/dev/null || stat -c%s "$apk") bytes, declared: $declared, all: $(echo $found)"
}

# The permissions tool/lighttool.toml declares, one per line: Chess's own, not the SDK's merged ones.
declared_permissions() {
  sed -nE 's/^permissions = \[(.*)\]$/\1/p' tool/lighttool.toml | tr ',' '\n' | tr -d ' "' | grep . || true
}

# W8: Light builds releases from the public commit. A release that declares Chess's own INTERNET while
# RelayConfig.URL is empty would ask for the network and never use it, so it isn't releasable. Keyed on
# lighttool.toml's declared permissions, since INTERNET is in every Tool's APK through Light's SDK.
check_relay() {
  local url
  url=$(sed -nE 's/^[[:space:]]*const val URL = "(.*)"$/\1/p' tool/src/main/kotlin/com/yarosz/chess/relay/RelayConfig.kt)
  if grep -qx 'android.permission.INTERNET' <<<"$(declared_permissions)"; then
    [ -n "$url" ] || die "relay: lighttool.toml declares INTERNET but RelayConfig.URL is empty: deploy the Relay and set it (relay/README.md)"
    [[ "$url" == https://* ]] || die "relay: RelayConfig.URL must be https://, got $url"
  fi
  echo "release-check: relay OK RelayConfig.URL=${url:-(empty)}, declared: $(declared_permissions | paste -sd' ' -)"
}

build_emulator_release() {
  scripts/emulator-build.sh "${gradle[@]}" :tool:assembleRelease
  [ -f "$emulator_apk" ] || die "no dev-signed release APK"
}

fresh_launch() {  # apk
  emu uninstall "$pkg" >/dev/null 2>&1 || true
  emu install "$1" >/dev/null || die "install $1"
  launch
}

launch() {
  emu shell am force-stop "$pkg"
  emu logcat -c
  emu shell monkey -p "$pkg" 1 >/dev/null 2>&1 || die "launch"
}

check_run() {
  build_emulator_release
  fresh_launch "$emulator_apk"
  drive skip
  drive wait "Tap a piece"
  drive shot "$out/run-0-first-puzzle.png"
  drive solve >"$out/run-solve.json"
  drive shot "$out/run-1-solved.png"
  drive about >"$out/run-about.txt"
  drive shot "$out/run-2-about.png"
  local date uid traffic
  date=$(python3 -c 'import json;print(json.load(open("tool/src/main/assets/pack/manifest.json"))["source"]["date"])')
  grep -q "Nothing leaves this phone." "$out/run-about.txt" || die "About has no privacy line"
  grep -q "dump of $date" "$out/run-about.txt" || die "About lacks the dump date $date"
  # The privacy line, measured: the Tool's uid has no row in the kernel's per-uid traffic counters.
  uid=$(emu shell pm list packages -U "$pkg" | tr -d '\r' | grep -oE 'uid:[0-9]+' | cut -d: -f2)
  traffic=$(emu shell dumpsys netstats detail | tr -d '\r' | sed -n '/^  mAppUidStatsMap:$/,/^  m/p' | awk -v u="$uid" '$1 == u')
  [ -z "$traffic" ] || die "the Tool used the network: uid rx/tx $traffic"
  echo "release-check: run OK minified release solved $(cat "$out/run-solve.json"), About shows the dump date $date," \
    "no network traffic for the Tool's uid"
}

check_upgrade() {  # ref
  local ref=$1 work sdk_sha
  work=$(mktemp -d)
  trap 'rm -rf "$work"' RETURN
  git archive "$ref" | tar -x -C "$work"
  sdk_sha=$(git ls-tree "$ref" light-sdk | awk '{print $3}')
  git -C light-sdk archive "$sdk_sha" | tar -x -C "$work/light-sdk"
  echo "sdk.dir=$android" >"$work/local.properties"
  # The emulator talks to the LightOS emulator app: the same swap as scripts/emulator-build.sh, on a throwaway copy.
  sed -i.bak 's/^serverPackage = .*/serverPackage = "com.thelightphone.sdk.emulator"/' "$work/tool/lighttool.toml"
  (cd "$work" && ./gradlew -q --console=plain :tool:assembleDebug)
  fresh_launch "$work/tool/build/outputs/apk/debug/tool-debug.apk"
  drive skip
  drive solve >/dev/null
  drive next
  drive solve >/dev/null
  drive state >"$out/upgrade-before.json"
  drive shot "$out/upgrade-1-before.png"

  build_emulator_release  # up to date with the tree; incremental after `run`
  emu install -r "$emulator_apk" >/dev/null || die "install over $ref"
  launch
  drive state >"$out/upgrade-after.json"
  drive shot "$out/upgrade-2-after.png"
  python3 - "$out/upgrade-before.json" "$out/upgrade-after.json" <<'PY' || die "the save did not survive the upgrade"
import json, sys
before, after = (json.load(open(p)) for p in sys.argv[1:])
assert len(before["history"]) == 2, f"expected 2 rated Puzzles before, got {before}"
assert before == after, f"before {before}\nafter  {after}"
PY
  echo "release-check: upgrade OK $(git rev-parse --short "$ref") -> this build keeps $(cat "$out/upgrade-after.json")"
}

check_scan() {
  # Generic patterns: local paths, device serials, hostnames, private addresses, e-mail addresses, keys.
  local pat='/Users/[A-Za-z]|/home/[a-z]+/|[A-Z]:\\Users\\|\bLP3[A-Z0-9]{8,}|\.ts\.net\b|\b[a-z0-9-]+\.local\b|\.lan\b'
  pat+='|\b192\.168\.[0-9]+\.[0-9]+|\b10\.[0-9]+\.[0-9]+\.[0-9]+\b|\b172\.(1[6-9]|2[0-9]|3[01])\.[0-9]+\.[0-9]+'
  pat+='|[A-Za-z0-9._%-]*[A-Za-z0-9]@[A-Za-z0-9-]+\.[A-Za-z]{2,}|BEGIN [A-Z ]*PRIVATE KEY|ghp_[A-Za-z0-9]{20}|sk_(live|test)_|AKIA[0-9A-Z]{12}|xox[bp]-'
  # Allowed: git's own no-reply addresses, the commit trailer, and the SDK's public dev-key path. Also
  # the Android emulator's fixed addresses, which name no machine: 10.0.2.2 is its alias for the host (a
  # debug build's local Relay, W8) and 10.0.2.3 its DNS (a negative test in v3's Relay client). Only
  # these two; any other 10.x address still fails. (Loopback, 127.0.0.1, never matches the patterns.)
  local allow='noreply@anthropic\.com|users\.noreply\.github\.com|lightsdk-dev\.jks|\b10\.0\.2\.[23]\b'
  local private="" hits
  if [ -n "${RELEASE_SCAN_PRIVATE:-}" ]; then
    [ -f "$RELEASE_SCAN_PRIVATE" ] || die "RELEASE_SCAN_PRIVATE names no file"
    private=$(grep -v '^[[:space:]]*$' "$RELEASE_SCAN_PRIVATE" | paste -sd'|' -)
  fi
  # Generic patterns match case-sensitively, private ones in any case.
  flag() {
    { grep -E "$pat" <<<"$1" || true; [ -z "$private" ] || grep -iE "$private" <<<"$1" || true; } \
      | grep -vE "$allow" || true
  }

  # 1. Tracked files as staged (the index: HEAD plus anything added for the next commit). git grep's
  #    regex has no \b here, so it lists every line and grep -E matches. The maintainer's name belongs
  #    on copyright lines only. This script is skipped: its patterns would match themselves.
  local lines
  lines=$(git grep --cached -nI -e . -- . ':!tool/src/main/assets/pack' ':!scripts/release-check.sh')
  hits=$(flag "$lines"; grep 'Nicolas' <<<"$lines" | grep -v 'Copyright' || true)
  [ -z "$hits" ] || die "tracked files:
$hits"

  # 2. Every commit's diff and message, on every branch. The Pack's Puzzle lines are skipped: FENs and
  #    ids are random-looking text that trips the patterns without carrying anything personal.
  lines=$(git log --all -p --text --format='commit %h%n%an <%ae>%n%B' -- . ':!tool/src/main/assets/pack' ':!scripts/release-check.sh')
  hits=$(flag "$lines" | head -20)
  [ -z "$hits" ] || die "history:
$hits"

  echo "release-check: scan OK $(git ls-files | wc -l | tr -d ' ') tracked files, $(git rev-list --all | wc -l | tr -d ' ') commits" \
    "${private:+(with $(grep -cv '^[[:space:]]*$' "$RELEASE_SCAN_PRIVATE") private patterns)}"
}

case "${1:-}" in
  apk) check_apk ;;
  relay) check_relay ;;
  run) check_run ;;
  upgrade) [ -n "${2:-}" ] || die "usage: scripts/release-check.sh upgrade REF"; check_upgrade "$2" ;;
  scan) check_scan ;;
  all) [ -n "${2:-}" ] || die "usage: scripts/release-check.sh all REF"; check_scan; check_relay; check_apk; check_run; check_upgrade "$2" ;;
  *) sed -n '2,16p' "$0" >&2; exit 2 ;;
esac
