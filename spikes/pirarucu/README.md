# Pirarucu vendoring spike (2026-09-27)

Checks whether Pirarucu's `pirarucu-common` (github.com/ratosh/pirarucu, GPLv3, commit 987dd02,
v3.4.0) can be vendored into a Light Tool. Verdict: viable.

- `vendor.sh` flattens `pirarucu-common` into `flat/engine/src`: it replaces the one `expect object
  PlatformSpecific` with `PlatformSpecific.kt`, deletes the UCI and tuning files, and marks
  `SearchOptions.stop` `@Volatile`.
- `flat/scanner` runs the Light plugin's own source-scan rules (copied from `LightSdkPlugin.kt`) over
  a source tree. Result: 0 violations for the original and the flattened tree.
- `flat/bench` runs perft, a timed search, and a stop-from-another-thread test.

Results on a Mac (M3 Pro, JDK 21, Kotlin 2.3.20, one thread, 16 MB hash): perft startpos depth 5 =
4,865,609 and Kiwipete depth 4 = 4,085,603, both exact; about 1.85M nodes per second (depth 22-23 in
10 s). Not yet built inside the Light plugin or run on the phone.

To rerun: clone Pirarucu at 987dd02 into `pirarucu/` here, run `sh vendor.sh`, then from `flat/`
run `gradle :bench:installDist` and `bench/build/install/bench/bin/bench perft|search|stop`, or
`gradle :scanner:run --args=<src dir>`.
