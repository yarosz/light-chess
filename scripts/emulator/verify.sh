#!/usr/bin/env bash
# Verify the LightPhone3-chess AVD on emulator-5556. Only ever talks to -s emulator-5556.
a="$HOME/Library/Android/sdk/platform-tools/adb"
s=emulator-5556
echo "avd:";      "$a" -s $s emu avd name | head -1
echo "density:";  "$a" -s $s shell wm density
echo "size:";     "$a" -s $s shell wm size
echo "app:";      "$a" -s $s shell dumpsys window displays | grep -m1 ' app=' | grep -oE 'app=[0-9]+x[0-9]+'
echo "nav:";      "$a" -s $s shell cmd overlay list | grep -i navbar
echo "navigation_mode (2=gestural):"; "$a" -s $s shell settings get secure navigation_mode
echo "priv-app:"; "$a" -s $s shell ls -l /system/priv-app/LightOSEmulator/
                  "$a" -s $s shell pm path com.thelightphone.sdk.emulator
                  "$a" -s $s shell dumpsys package com.thelightphone.sdk.emulator | grep -m1 'uid='
echo "home:";     "$a" -s $s shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
                  "$a" -s $s shell dumpsys window | grep -m1 mCurrentFocus
echo "animations:"; for k in window_animation_scale transition_animation_scale animator_duration_scale; do "$a" -s $s shell settings get global $k; done
echo "dns:";      "$a" -s $s shell ping -c1 -W5 lichess.org
