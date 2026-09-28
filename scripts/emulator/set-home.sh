#!/usr/bin/env bash
# Make the LightOS emulator the home activity on emulator-5556 and wait until it is persisted.
a="$HOME/Library/Android/sdk/platform-tools/adb"
s=emulator-5556
"$a" -s $s root >/dev/null; sleep 2; "$a" -s $s wait-for-device
"$a" -s $s shell cmd package set-home-activity com.thelightphone.sdk.emulator/.MainActivity
"$a" -s $s shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
# PackageManager writes package-restrictions.xml lazily; wait for it before any kill.
for i in $(seq 1 30); do
  if "$a" -s $s shell grep -q thelightphone /data/system/users/0/package-restrictions.xml; then
    echo "persisted after ~${i}s"; "$a" -s $s shell sync; exit 0
  fi
  sleep 1
done
echo "home activity not persisted after 30s" >&2; exit 1
