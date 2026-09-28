# LightPhone3-chess AVD: recipe

A second LP3-shaped emulator for the chess Tool on port 5556. It runs next to `LightPhone3` (5554) without touching it.

## Why a fresh build and not a copy of the overlay

While `LightPhone3` runs, qemu holds all its `*.qcow2` overlays open read-write (`lsof` shows FD mode `u`), and `userdata-qemu.img.qcow2` is still being written. A copy made then can be inconsistent. So the clone reuses only the static `config.ini` and repeats DECISIONS D2 on the same system image (`system-images;android-34;default;arm64-v8a`, test-keys).

You can copy the overlays once `LightPhone3` is shut down. The recipe below does not need that.

## 1. Create the AVD (LightPhone3 files are only read)

```bash
D=~/.android/avd/LightPhone3-chess.avd
mkdir "$D"
sed -e 's/^avd.id=.*/avd.id=LightPhone3-chess/' -e 's/^avd.name=.*/avd.name=LightPhone3-chess/' \
    ~/.android/avd/LightPhone3.avd/config.ini > "$D/config.ini"
printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/LightPhone3-chess.avd\ntarget=android-34\n' "$D" \
    > ~/.android/avd/LightPhone3-chess.ini
emulator -list-avds   # LightPhone3, LightPhone3-chess
```

(`config.ini` already has `hw.lcd.density=480` and 1080x1240.)

## 2. Boot on 5556 (use these flags on every boot)

```bash
nohup emulator -avd LightPhone3-chess -port 5556 -writable-system -no-snapshot -no-boot-anim \
  -dns-server 1.1.1.1,8.8.8.8 -no-window > emu-5556.log 2>&1 &
bash wait-boot.sh          # polls adb -s emulator-5556 for sys.boot_completed
```

Leave out `-no-window` to get a window.

## 3. One-time setup (D2). Every adb call uses `-s emulator-5556`

```bash
A="adb -s emulator-5556"
$A root && $A wait-for-device && $A remount     # first time: "Overlayfs enabled ... reboot"
bash wait-boot.sh reboot
$A root && $A wait-for-device && $A remount
$A shell mkdir -p /system/priv-app/LightOSEmulator
$A push ~/dev/light-sdk/sdk/emulator/build/outputs/apk/debug/emulator-debug.apk \
        /system/priv-app/LightOSEmulator/LightOSEmulator.apk
$A shell chmod 644 /system/priv-app/LightOSEmulator/LightOSEmulator.apk
bash wait-boot.sh reboot                        # PackageManager picks it up as system, uid 1000
$A shell settings put global window_animation_scale 0
$A shell settings put global transition_animation_scale 0
$A shell settings put global animator_duration_scale 0
$A shell wm density 480
$A shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
bash set-home.sh    # root, set-home-activity com.thelightphone.sdk.emulator/.MainActivity, wait until persisted
```

Gotcha: `set-home-activity` is written to `package-restrictions.xml` lazily. The first time, `emu kill` ran a few seconds after setting it and the setting was lost; Launcher3 was home on the next boot. `set-home.sh` waits until the file names the package before it returns.

## 4. Verify

```bash
bash verify.sh
```

Expected: avd `LightPhone3-chess`, density 480, size 1080x1240, `app=1080x1168`, `[x] ...navbar.gestural` and `navigation_mode` 2, `/system/priv-app/LightOSEmulator/LightOSEmulator.apk` with `uid=1000`, HOME resolves to `com.thelightphone.sdk.emulator/.MainActivity`, animation scales 0 0 0, `ping lichess.org` resolves and gets a reply. The emulator's user-mode network garbles ICMP timings, so the ping only proves DNS and reachability.

## 5. Shut down

```bash
adb -s emulator-5556 emu kill
adb devices     # emulator-5554 still listed, unchanged
```

Helper scripts `wait-boot.sh`, `set-home.sh` and `verify.sh` are in this directory. They only ever call `adb -s emulator-5556`.
