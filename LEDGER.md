# Ledger

STATUS: v1 feature-complete (0.1.0, versionCode 1; branch feat/v1-release, now with the wheel key-up
fix R4.17), checked on the LP3, pending LP3 photos for the README and the maintainer's submission. v2: Pirarucu is GO (ADR 0001
accepted: LP3 non-debuggable 558K nps P50, depth 18 in 3 s). feat/v2-play merges feat/v1-release and
feat/v2-bench as v2's base, and now also merges the Levels (PR 3), the game record (PR 5 core) and
the Book (PR 6), and the game screen (PR 4, with R4.16/R4.17), reviewed and checked on the LP3: the
computer is playable at every Level. Next is the v3 client.
v3: the Relay is done on feat/v3-relay (protocol 1.0, not deployed); the client comes after v2.
LAST SESSION: 2026-09-28

## HANDOFF (read first when resuming)
- Branches, all signed and unpushed (there is no remote yet):
  - feat/v1-core → feat/v1-pack → feat/v1-board → feat/v1-puzzles → feat/v1-release: the v1 stack,
    0.1.0, checked on the LP3.
  - feat/v2-engine → feat/v2-bench (Pirarucu vendored; benchmark; ADR 0001 accepted).
  - feat/v2-levels, feat/v2-record, feat/v2-book: each merged into feat/v2-play.
  - feat/v2-play @ 2e6c4c4: v2's integration branch = v1-release + bench + levels + record + book +
    game screen + the key-up and strip fixes (fast-forwarded to fix/v2-keyup). 289 tests, light-build
    files=124.
  - feat/v2-game (from feat/v2-play): v2 PR 4, the game screen. 278 tests, light-build files=122. Rulings:
    decision log "v2 PR 4" (R4.1-R4.15); the screen: DESIGN.md "The game screen".
  - feat/v1-release was fast-forwarded to fix/v1-keyup (25530a1): 165 tests, light-build files=57.
  - fix/v1-keyup (from feat/v1-release): the wheel's key-up fix for 0.1.0 (R4.17). A wheel click in
    Review also turned the LP3's flashlight on, because LightOS got the key-up of a press the screen
    had taken. Every wheel screen now extends `WheelViewModel` (`TakenKeys`, `TakenKeysTest`).
  - fix/v2-keyup (from feat/v2-game, merges fix/v1-keyup): R4.17 on the game screen and the Games
    Review too, and R4.16, the game strips on one line in Akkurat (Takeback only in the Menu,
    "Thinking", Latest alone in Review; StripFitTest recalibrated on the LP3 screencaps). feat/v2-game
    (bbaad95) is left behind it; feat/v2-play carries both.
  - feat/v3-relay: the Relay (TypeScript Worker + Durable Object, 93 tests, dry-run OK), from the v1
    pack commit. Not merged into feat/v2-play; not deployed.
- v2 PR 4 is done and merged into feat/v2-play (brief `docs/design/briefs/v2-pr4-game-screen.md`),
  reviewed by the orchestrator (GameFlow, GameOwner's search and save races, screenshots). Checked on the emulator: a Level 1 Game as White to
  checkmate, Level 8 at 30 s with Move now and a Takeback in both states, a Game Hint, a declined draw
  offer, Resign's two taps, a force-stop mid-think then relaunch (the computer resumed and moved),
  Menu → Games (Review of a finished Game) / Moves / About (engine and Book credits), the board flip,
  Puzzles ↔ Play the computer, and "Tap again to replace".
- Checked on the LP3 (2026-09-28, debug builds). PR 4: Level 8 at 30 s from the Book into a search;
  Move now (moveNow=true after 24.6 s); keep-screen-on held through the think; the wheel's Review in a
  Game and a click back to the latest; a force-stop mid-think then relaunch (resumed, moved after
  30 s). The fixes: a click in Review (a Game and a Puzzle, both lines) returns to the latest with no
  torch event (dumpsys media.camera); outside Review a click still toggles the torch; "Your move" +
  Hint + Menu, "Thinking" + Move now + Menu and "Review · n of m" + Latest each on one line in
  Akkurat. Not yet seen on the phone: "White to move" next to Hint, Solution and Menu (two lines
  expected), a Result's two lines, the 200 ms slide, and the Games page's Review.
- Next: the v3 Kotlin client against the Relay's `docs/protocol.md` (feat/v3-relay), from feat/v2-play.
- Deferred from PR 4: "Play from here" (F7) from a Puzzle's start (R4.15: its own entry on the puzzle
  screen, a decided-Position test, "Ends your current game"); a draw offer while the computer thinks
  (R4.5: on the user's Move only); exact replay at Level 8 (clock-bound). Not measured: the strength
  effect of clearing the engine's table before each Move at Levels 1-7 (R4.8), so a rerun of
  LevelCalibrationTest with the same clearing would confirm the Level gaps.
- Slated for a follow-up, not scheduled (maintainer, 2026-09-28):
  - "Play a stranger", v3.x after friend play ships. It is anonymous matchmaking with no accounts and
    no visible lobby: the Relay's matchmaker hands a waiting ticket to the next phone that asks, and
    both get seats as if a code were redeemed. It reopens C6 ("friends only"), so it gets an ADR.
    Open: ticket expiry (about 7 days), matching on Days per Move only, an optional coarse strength
    band, one ticket per phone, and the brief IP rate limit. No chat keeps harassment out; timeouts
    handle stalling.
  - LP3-only play can only be an honour rule, because the Relay can't tell an LP3 from a sideloaded
    APK.
- Working rules learned:
  - Builders run with `isolation: worktree`. The orchestrator must not EnterWorktree while a
    non-isolated agent works in its worktree: that moves the harness pin and blocks the agent.
  - 1Password signing sometimes needs the maintainer at Touch ID: on "agent returned an error", leave
    work staged and ask; never commit unsigned.
  - Long gradle and light-build runs go in the background (the maintainer backgrounds long blocking
    commands).
  - The LP3's serial is in the umbrella PLATFORM.md (never in this public repo). Take the lease with noclobber and message
    light-doom and the Reader session before and after. The chess emulator is LightPhone3-chess on
    emulator-5556; never touch 5554.
- Waiting on the maintainer (none blocks development):
  - Light: a GPLv3 Tool (and GPL alongside the SDK's proprietary ML Kit); production push; an
    alert/badge method; a privacy statement; listing requirements; the SDK's 1 s splash; and the
    scanner's bare-`javaClass` gap (light-sdk).
  - A Cloudflare account and URL to deploy the Relay (relay/README.md).
  - LP3 photos for the README, then the v1 submission.
  - Maybe: release the CC0 opening Book as its own repo (builder MIT, book.bin CC0) after checking
    whether a CC0 Polyglot book already exists.
  - Stockfish: approved for later, for real Elo labels (decision log "Stockfish for calibration").

## Where things are
- Design: `CONTEXT.md` (glossary), `docs/adr/0001-0003`, `docs/design/decision-log.md`. The log holds
  every ruling and each contradiction it resolved; read it before reopening a decision.
- Repo: the light-reader template (`AGENTS.md`, `mise.toml`, `scripts/ci.sh`, `light-build.sh`,
  `emulator-build.sh`, `domain-drift.sh`), light-sdk submodule at v0.1.2, `tool/lighttool.toml`
  (com.yarosz.chess, portrait, serverPackage com.lightos, no permissions).
- Rules core: `tool/src/main/kotlin/com/yarosz/chess/rules/` (pure Kotlin). Perft exact on the start
  position, Kiwipete and positions 3-6; property tests over 400 random Games. `-Dperft.deep=true` adds
  Kiwipete d5 and position 6 d5.
- Board: `tool/src/main/kotlin/com/yarosz/chess/board/`. `PositionView` (Compose Canvas), `MoveInput`
  (pure input state machine), `Review` (wheel), `Shades`/`Marks` (constants, `ShadesTest` enforces
  D10), `Strip`, and `CburnettPieces`, generated by `scripts/build-pieces.py` from
  `third_party/cburnett` (BSD-3; CI runs `--check`). `DESIGN.md` holds the values, the marker
  readability rule and the input decisions; copy lives in `UiCopy`.
- Puzzle flow: `puzzles/Attempt.kt` (pure state machine: A3-A6), `PuzzleFlow.kt` (scoring, selection,
  Missed, Reset rating, the F1 carry-over), `Glicko2.kt` (checked against Glickman's worked example),
  `PuzzleData`/`PuzzleStore` (`puzzles.json`: temp + rename, `.bak`, lenient read). `PuzzleOwner` is the
  process-wide owner (clock, file, keep-screen-on); `ChessScreen` (seed screen + puzzle) and
  `MenuScreen` are views onto it. DESIGN.md "The puzzle flow" lists the choices the log doesn't make.
- Pack: `scripts/build-pack.py` builds `tool/src/main/assets/pack/` (25 Band files + manifest.json,
  46,971 Puzzles, 6.5 MB) from the pinned 2026-09-09 dump; `docs/pack.md` says how and why. Bands the
  A1 filter leaves short are filled from R1.4's filter (13,264 Puzzles, counted per Band in the
  manifest): a ruling for the next product review. Loader: `tool/src/main/kotlin/com/yarosz/chess/
  puzzles/` (`Pack`, `Puzzle`); `CommittedPackTest` parses every Puzzle with the rules core.
- Engine spikes: `spikes/pirarucu` (viable, ~3050 CCRL, 1.85M nps on a Mac core) and `spikes/karballo`
  (viable, ~400 Elo weaker, 1.5/20 head-to-head). Pirarucu is the v2 engine; Karballo is the fallback
  for a fully MIT Tool.
- Release: `RELEASING.md` (checklist), `scripts/release-check.sh` (`scan`, `apk`, `run`, `upgrade REF`)
  driving the emulator through `scripts/release-drive.py`, `docs/release-notes/`. The scan takes
  private patterns from a file outside the repo (`RELEASE_SCAN_PRIVATE`). About's copy is
  `UiCopy.about`; NOTICE carries the same library list.

- Engine (feat/v2-engine): `tool/src/main/kotlin/vendor/pirarucu/` from `scripts/vendor-pirarucu.py`
  (`--check`, `--parity`; provenance and changes in `vendor/pirarucu/README.md`); `engine/` holds
  `Engine`, `PirarucuEngine` and `EngineHost.shared`. `ParityTest` pins upstream's fixed-depth node
  counts. Benchmark: `src/benchmark/.../bench/` (the `benchmark` build type only), `mise run bench
  <serial>` (`-d`: debuggable twin), `mise run bench-jvm` (Mac: ~1.9M nps, depth 14 in 195 ms P50),
  `scripts/bench-test.sh`. LP3 (non-debuggable): 558K nps P50 / 862K P90, depth 14 in 693 / 1,151 ms,
  3 s reaches depth 18 / 22, A78 cores 6-7, heap limit 128 MB, thermal 0; 3.6x the debuggable build.
  Facts and choices: decision log, "v2 PR 1" and "v2 PR 2".
- Game record (v2 PR 5 core, feat/v2-record): `tool/src/main/kotlin/com/yarosz/chess/games/`.
  `GameRecord` (Game + Side, Level, ThinkTime, Takebacks, Game Hints), `Pgn` (writer + reader; tags
  and comment commands in its KDoc), `GameData`/`GameStore` (`games.json`: the Game in progress as
  PGN + FEN checkpoint, the last 50 finished). Shared save pattern in `SaveFile` (PuzzleStore uses it
  too). The game screen (feat/v2-game) wires it; the record also keeps the Game's seed and the
  computer's evals. Choices: decision log, "v2 PR 5 core".

- Book (feat/v2-book): `tool/src/main/assets/book/` (`book.bin`, Polyglot, 7,082 entries, 113,312
  bytes, + `book-manifest.json`) from the pinned CC0 Lichess 2018-01 dump, streamed by
  `scripts/build-book.sh` (`mise run book`) through `scripts/book-build/` (Kotlin, our core).
  `Position.polyglotKey` (`rules/`, table from `scripts/polyglot-random64.sh`), `book/Book` (reader,
  shared with the build) and `book/BookPolicy` (per Level, pure, seeded). `docs/book.md`; decision
  log "Opening book rulings" and "v2 PR 6". The game screen (feat/v2-game) calls BookPolicy first
  and stores the seed with the Game (PGN tag `Seed`).
- Game screen (feat/v2-game, v2 PR 4): `games/GameFlow.kt` (pure: `GameState`, `GameFlow`,
  `DrawJudge`, `ComputerReply`; `GameFlowTest`), `GameOwner` (process-wide: the computer on
  `EngineHost.shared`, Game Hints, the clock, `games.json`), `ModeOwner` (`mode.txt`: the mode last
  used), `GameStrip` (the strip by context; StripFitTest), `ChessScreen` (either mode), `MenuScreen`
  (game Menu, new-game page, Games, Moves), `GameReviewScreen` (a finished Game). DESIGN.md "The game
  screen"; decision log "v2 PR 4".
- Levels (feat/v2-levels): `engine/Level.kt` (Level -> node budget, depth cap, N, margin; ThinkTime),
  `engine/LevelPlayer.kt` (the pick, seeded per Game and Ply; MoveNow; `LevelMove.trueScore` for G1),
  `EngineHost.play`. Calibration: `LevelCalibrationTest` (`-Dcalibrate=blunders,random,ladder,anchor`),
  `scripts/level-elo.py`, Karballo line server `spikes/karballo/flat/serve`. Values, tables and method:
  `docs/levels.md` and decision log "v2 PR 3". No Elo labels (the Karballo anchor is too loose).
- Licence: GPL-3.0-or-later, relicensable later. Outside code needs a copyright assignment
  (CONTRIBUTING.md).
- Emulator: AVD `LightPhone3-chess` on emulator-5556 (`scripts/emulator/RECIPE.md`). Scripts find it by
  AVD name (`scripts/chess-emu.sh`, `CHESS_AVD`). Never use emulator-5554, which belongs to the Reader.
  On a fresh boot, dismiss the ImmersiveModeConfirmation dialog (`mise run ui tap "GOT IT"`). The LP3 is
  shared through `~/.cache/lp3-lease`; the protocol is in the umbrella PLATFORM.md.

## Next
1. Checked on the LP3 (2026-09-28): the wheel's Review in free play and in a Puzzle, the wheel
   scrolling About, the two-line strip showing "Tap a piece, then a square" in full, and the time to
   the first Puzzle on resume: about 1,035 ms from process start (debug build), just after
   LightActivity's 1 s splash (restated target <= 1.5 s, decision log "v1 PR 4 rulings"). Still open
   on the LP3: a photo of the shades through the grayscale filter (D10) and `signoff/lp3` in `ci.sh`.
2. Release, by the maintainer: LP3 photos into `docs/screenshots/` and the README; then RELEASING.md's
   checklist and the submission in Light's portal (discussion #204; the portal isn't live yet).
3. For the next product review: with RD 500, volatility 0.09 and one Puzzle per rating period, the
   deviation settles near 73-74 (about 48 Puzzles to lose the "?"), so the 45 floor is never reached.
   Also from PR 5: "White to move" wraps next to three buttons on the LP3; a shorter strip (fewer
   buttons) is the alternative to a two-line status.
4. v2: Levels (PR 3), the game record (PR 5), the Book (PR 6) and the game screen (PR 4,
   feat/v2-game, with the engine's credit in About, D7) are built. Left for v2.x: "Play from here"
   (F7, decision log R4.15).
5. Licence: Light's SDK bundles Google's proprietary ML Kit barcode library (~20 MB). With GPLv3
   Pirarucu in the same APK this is a compatibility question for Light (the maintainer asks). Our own
   code can carry a linking exception; Pirarucu's can't. Karballo (MIT) stays the fallback.

1. v1 PR 3: board + input (D9, R1.6, R1.7, D10), with DESIGN.md. It adds `signoff/lp3` to `ci.sh`
   under the lease. Measure the time to the first Puzzle on the LP3 there (F10, target < 500 ms;
   the JVM takes about 5 ms).
2. v1 PRs 4-5, per decision-log D9. PR 4 wires `Pack` (already built in `ChessScreen`) into the
   puzzle flow and keeps `packSha256` in the save file (A8, F1).
3. v2 PR 2 (E11): run `mise run bench <LP3 serial>` under the phone lease, record nps, time to depth,
   the engine thread's cores and the heap limit; GO/NO-GO for Pirarucu (else Karballo). v2 PR 3's
   time estimates use the LP3 figures handed to it (558K nps P50, depth 14 in 693 ms); if PR 2's
   recorded numbers differ, re-check that Level 7 (~240K nodes per Move) stays under 1 s.
4. v2 PR 4 (game screen): done on feat/v2-game (`EngineHost.play` + `stop()` for Move now, G1 on
   `trueScore`).

## Open outside questions (none blocks development)
- Light: will they sign a GPLv3 Tool; is production push live for Tools; is an alert or badge method
  planned; is a privacy statement needed; what does a listing need?
- light-sdk: the reflection scan misses a bare `javaClass` call (`LightSdkPlugin.kt:119`). Under
  Light's AI policy, the maintainer must report it personally.
- light-sdk: every Tool's APK gets INTERNET, CAMERA and six more permissions merged in from the SDK's
  libraries (OkHttp, Google datatransport, WorkManager, Media3, CameraX), whatever `lighttool.toml`
  declares. So About says "Chess never uses the network" rather than "No network permission" (D5
  holds for what the Tool declares). The SDK also bundles Google's proprietary ML Kit barcode binary
  (`libbarhopper_v3.so`, about 20 MB of the 28 MB APK across four ABIs): part of Light's GPL question.
