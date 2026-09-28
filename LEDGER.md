# Ledger

STATUS: design settled; next is v1 PR 1. The engine choice is Pirarucu, pending the LP3 benchmark.
LAST SESSION: 2026-09-27

## Where things are
- Design: `CONTEXT.md` (glossary), `docs/adr/0001-0003`, `docs/design/decision-log.md`. The log holds
  every ruling and each contradiction it resolved; read it before reopening a decision.
- Engine spikes: `spikes/pirarucu` (viable, ~3050 CCRL, 1.85M nps on a Mac core) and `spikes/karballo`
  (viable, ~400 Elo weaker, 1.5/20 head-to-head). Pirarucu is the v2 engine; Karballo is the fallback
  for a fully MIT Tool.
- Licence: GPL-3.0-or-later, relicensable later. Outside code needs a copyright assignment
  (CONTRIBUTING.md).
- Emulator: AVD `LightPhone3-chess` on emulator-5556 (`scripts/emulator/RECIPE.md`). Never use
  emulator-5554, which belongs to the Reader. The LP3 is shared through `~/.cache/lp3-lease`; the
  protocol is in the umbrella PLATFORM.md.

## Next
1. v1 PR 1: the light-reader repo template (AGENTS.md, mise tasks, ci.sh, emulator-build.sh,
   light-build.sh), `tool/lighttool.toml` (id com.yarosz.chess, serverPackage com.lightos, portrait),
   the light-sdk submodule at a tagged version, and the rules core with perft. Then the Light builder
   rehearsal on a committed HEAD.
2. v1 PRs 2-5, per decision-log D9.
3. v2 opens with Pirarucu vendoring plus the LP3 benchmark (the go/no-go gate, E11).

## Open outside questions (none blocks development)
- Light: will they sign a GPLv3 Tool; is production push live for Tools; is an alert or badge method
  planned; is a privacy statement needed; what does a listing need?
- light-sdk: the reflection scan misses a bare `javaClass` call (`LightSdkPlugin.kt:119`). Under
  Light's AI policy, the maintainer must report it personally.
