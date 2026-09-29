# Pirarucu (vendored)

The computer opponent's search is Pirarucu's `pirarucu-common`, by ratosh: github.com/ratosh/pirarucu
at commit 987dd02c6d5cf1fa913ec92262aab42de38c96c2 (v3.4.0, 2020), GPL-3.0 (`LICENSE` here, verbatim
from upstream). Upstream is inactive, so this copy is ours to maintain (ADR 0001).

- **Where:** `tool/src/main/kotlin/vendor/pirarucu/`, one directory per package under `pirarucu`
  (`vendor/pirarucu/search/MainSearch.kt` holds package `pirarucu.search`). Package names stay
  upstream's, so unchanged files are byte-for-byte upstream. The copy must sit under `src/main/kotlin`,
  because Light's plugin forbids extra source directories and scans every file there; Light's extractor
  takes only `.kt` files from it, so this README and the licence live here instead.
- **Regenerate or check:** `scripts/vendor-pirarucu.py` fetches upstream into `build/pirarucu` and
  rewrites the tree; `--check` fails if the committed tree differs from what it generates.
- **Changes** (and only these; each changed file starts with a "modified" notice, GPLv3 section 5a):
  - `util/PlatformSpecific.kt`: the `expect object` becomes a plain stdlib object. Its JVM `actual` used
    `java.lang` and reflection; applyConfig, exit, gc, getVersion and formatString are dropped, as
    nothing left calls them.
  - Deleted: `uci/` (the UCI front end), `search/SimpleSearchInfoListener.kt` (stdout) and `util/epd/`
    (tuning).
  - `search/SearchOptions.kt`: `stop` is `@Volatile`; a node budget (`nodeLimit`) and the root moves to
    skip (`excludedRootMoves`).
  - `search/MainSearch.kt`: the node budget is checked next to the time limit; the ply-0 move loop skips
    excluded root moves (top-N sampling, decision E3); the root records its best move directly, and the
    iterative-deepening loop keeps the move, score and depth of the last completed iteration
    (`completedMove`), instead of reading them back from the transposition table.
- **Parity:** with no node budget and no exclusions the search is upstream's, node for node.
  `scripts/vendor-pirarucu.py --parity` builds upstream (pirarucu-common plus its JVM `actual`) and the
  vendored copy as two plain JVM programs (`scripts/pirarucu-parity/`), runs 25 fixed-depth searches
  (5 positions, depths 8, 9, 10, 12 and 14) on both, and compares them with each other and with
  `tool/src/test/resources/engine/parity.txt`, which `ParityTest` checks on every unit-test run.
