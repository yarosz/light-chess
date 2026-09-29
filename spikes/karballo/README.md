# Karballo vendoring spike (2026-09-27)

Checks Karballo (github.com/albertoruibal/karballo, MIT, commit a709a7e, Kotlin 1.2.21) against the
Pirarucu spike in `../pirarucu`, measured the same way. Verdict: viable with work, but much weaker.

- `vendor.sh` copies `karballo-common` plus `karballo-jvm`, deletes the UCI, thread, file and PGN
  code, and adds `overlay/LightPlatformUtils.kt` (seedable `kotlin.random`) and
  `overlay/PolyglotBook.kt` (binary search over an in-memory `.bin`). It fixes 15 deprecated
  `toLowerCase`/`toUpperCase` calls, marks `stop`, `thinkToTime` and `thinkToNodes` `@Volatile`, and
  synchronizes `BitboardAttacks.getInstance()`.
- The Light plugin's copied scan rules report 0 violations. It misses a bare `javaClass` call,
  because the regex needs a dot before it. The call was removed anyway.

Mac results (M3 Pro, one thread, 16 MB hash):

| | Karballo | Pirarucu |
|---|---|---|
| perft startpos d5 / Kiwipete d4 | exact / exact | exact / exact |
| nodes per second, 10 s | 1.6M (depth 17) | 1.85M (depth 22-23) |
| time to depth 12 | 0.7-1.0 s | 0.05-0.1 s |
| 20 games at 250 ms per move (`flat/match.log`) | 1.5 / 20 | 18.5 / 20 |

The built-in limiter (Elo 500-2100) randomly rejects better moves at every node of the search, and
it costs full CPU at every setting. `flat/elo.log` has its measured centipawn loss by Elo. The
bundled Fruit `book_small.bin` has an unclear licence, so it is not used.

To rerun: clone Karballo at a709a7e into `karballo/`, run `sh vendor.sh`, then from `flat/` with a
JDK 21 `JAVA_HOME` run `gradle :bench:installDist :h2h:installDist` and
`bench/build/install/bench/bin/bench perft|search|stop|nodes|book <bin>` or
`h2h/build/install/h2h/bin/h2h match 250|elo 300`. The match also needs the flattened Pirarucu
from `../pirarucu`.
