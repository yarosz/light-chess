# Ledger

STATUS: v1 PR 1 built on branch feat/v1-core (skeleton + rules core, perft exact). Next is v1 PR 2.
The engine choice is Pirarucu, pending the LP3 benchmark.
LAST SESSION: 2026-09-27

## Where things are
- Design: `CONTEXT.md` (glossary), `docs/adr/0001-0003`, `docs/design/decision-log.md`. The log holds
  every ruling and each contradiction it resolved; read it before reopening a decision.
- Repo: the light-reader template (`AGENTS.md`, `mise.toml`, `scripts/ci.sh`, `light-build.sh`,
  `emulator-build.sh`, `domain-drift.sh`), light-sdk submodule at v0.1.2, `tool/lighttool.toml`
  (com.yarosz.chess, portrait, serverPackage com.lightos, no permissions).
- Rules core: `tool/src/main/kotlin/com/yarosz/chess/rules/` (pure Kotlin). Perft exact on the start
  position, Kiwipete and positions 3-6; property tests over 400 random Games. `-Dperft.deep=true` adds
  Kiwipete d5 and position 6 d5. The screen is a placeholder that draws the start Position as letters.
- Engine spikes: `spikes/pirarucu` (viable, ~3050 CCRL, 1.85M nps on a Mac core) and `spikes/karballo`
  (viable, ~400 Elo weaker, 1.5/20 head-to-head). Pirarucu is the v2 engine; Karballo is the fallback
  for a fully MIT Tool.
- Licence: GPL-3.0-or-later, relicensable later. Outside code needs a copyright assignment
  (CONTRIBUTING.md).
- Emulator: AVD `LightPhone3-chess` on emulator-5556 (`scripts/emulator/RECIPE.md`). Scripts find it by
  AVD name (`scripts/chess-emu.sh`, `CHESS_AVD`). Never use emulator-5554, which belongs to the Reader.
  On a fresh boot, dismiss the ImmersiveModeConfirmation dialog (`mise run ui tap "GOT IT"`). The LP3 is
  shared through `~/.cache/lp3-lease`; the protocol is in the umbrella PLATFORM.md.

## Next
1. v1 PR 2: pack script + committed pack `.txt` + loader tests (D9, A1, A2).
2. v1 PRs 3-5, per decision-log D9. PR 3 adds `signoff/lp3` to `ci.sh` under the lease.
3. v2 opens with Pirarucu vendoring plus the LP3 benchmark (the go/no-go gate, E11).

## Open outside questions (none blocks development)
- Light: will they sign a GPLv3 Tool; is production push live for Tools; is an alert or badge method
  planned; is a privacy statement needed; what does a listing need?
- light-sdk: the reflection scan misses a bare `javaClass` call (`LightSdkPlugin.kt:119`). Under
  Light's AI policy, the maintainer must report it personally.
