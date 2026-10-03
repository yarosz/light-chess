# Ledger

STATUS: released v0.1.0 (Puzzles), v0.2.0 (the computer), v0.3.0 (Play a friend) and v0.3.1 (the
v3 review follow-ups: the Relay's limits and plain-HTTP refusal L1-L5, Time Left and a deleted Game
W12/W13), each a tagged commit on main with a notes-only GitHub Release. The Relay is live at
`https://chess-relay.yarosz.com` (W11, protocol 1.0). Merged on main since v0.3.1, unreleased: #16
Navigation D (a fixed Home, back on the left, this board's actions on the right; N1-N10), #18 Layout
E (LightOS's top bar over the board, actions below; E1-E5), #19 E6, #20 the Puzzles page and Pieces
in every board's Menu (N11-N20, with N21-N23), #21 a Missed tap during the Pack check replays
when it lands (N23), #22 Captured Pieces from each edge, action words centred below (E12-E15),
#23 README screenshots, #24 AGENTS.md's INTERNET line (W1), #25 no coordinate on a marked square
(K1), #26 the computer's Move slides in once (no replay after the Menu). Next, in order:
1. Done: the LEDGER refresh (#27) and the domain pass (#28, tag `domain-pass/0.4.0`).
2. Release 0.4.0, the release PR (versionCode 5, notes in `docs/release-notes/0.4.0.md`), through
   RELEASING.md's checklist. The LP3 checks still open are listed under LP3 CHECKS below.
3. v3 PR 3, the live WebSocket (see "Next" under HANDOFF below).
FOR THE OWNER (none blocks development): the submission to Light's portal (v0.1.0 is safe to submit;
hold v0.2.0 and later until Light answers the GPLv3 + ML Kit licence question); optional Always Use
HTTPS/HSTS on the Relay's zone (the Worker already refuses plain HTTP, L3).
FOLLOW-UPS (low, from code review; none blocking):
- #21: drop a kept Missed tap on `onAppPause` too; a single-threaded main dispatcher in
  `PuzzleOwnerMissedTest`; tests for two taps, leave-return-tap and a failing check; make
  `replayMissed`'s `whenLanded` non-null.
- #22: measured by ink rather than box, a 16 dp Captured Pieces drawing might fit (0.9 dp either
  side); the size stays 15 dp by E13's box rule.
- #26: define the slide's kept time once on `Motion`, so `PuzzleOwner`'s and `GameOwner`'s
  `SLIDE_KEPT_MS` can't drift; test that the slide stays published at least its minimum.
- v3 review: `localhost` is accepted by `RelayConfig.allowed` but not by the debug network security
  config; a cold-started `friend-send` job REPLACEs itself once (FriendOwner init).
- Navigation D: `Rows.HEIGHT` (48 dp) is the wheel step while Home's and the pages' rows measure
  about 53 dp, so a detent scrolls slightly under a row (predates Navigation D).
LP3 CHECKS. Done on the LP3 on 2026-10-03, in Akkurat: the arrow's ink at pixels 48-82, with the
Menu mark level with it; Home's first view (five rows fit); the Pieces preview in a board's Menu; a
fast scrub back past a Review's start leaves the brightness alone (N20); each button's target is the
whole row (#22: Hint and Solution span 1056-1240 px). Still open, to confirm or drop: the widest
top-bar title ("Tap a piece, then a square", 234 of 240 dp in the stand-in; it shows only on a first
Puzzle); the click on the live Position still toggles the flashlight (owner); Akkurat's label ink
under a crowded Captured Pieces row with a descender label (Accept, Retry), no clipping at the
bottom edge (E13-E15; needs a Correspondence Game); a Correspondence Game's Menu under Navigation D,
and the rounded Piece Set on a Correspondence Game (need a second phone); in play, the computer's
Move doesn't slide again after the Menu (#26) and a Missed replay opens at once (#21); a Result's
two lines and "Rematch · Accept · Decline" fitting the action row. From v1: the greys and both Piece
Sets on the physical panel (owner's eyes or photos), and Hint taps during a reply don't delay it.
DONE (details in the PRs and the decision log): the v1 stack and 0.1.0 (LP3-checked 2026-09-29),
the v2 forward merge (M1-M7) and 0.2.0, the Relay deploy and v3 (#3, #10 P3) and 0.3.0, 0.3.1, then
#16-#26 above.
LAST SESSION: 2026-10-03

## HANDOFF (read first when resuming)
- Every feature branch (the v1 stack, v2's engine, Levels, record, Book and game screen, v3's
  Relay, client and Play a friend) is merged to main and released. Rulings: decision log "v2 PR 4"
  (R4.1-R4.17), "v3 PR 1" (V1-V15), "v3 PR 2" (W1-W10, Y1-Y14); screens: DESIGN.md.
- Next: v3 PR 3, the live WebSocket (`/live`, G3: ping every 5 s while the board shows, closed in
  onAppPause, "Live · Your move" / "Live · Their move"), which replaces Y12's one-minute board sync.
  Done on the LP3 (2026-09-29): LightOS disables Doze (PLATFORM.md). A one-off friend-send job
  delivered a Move that had failed to send once a local Relay was back (via adb reverse), with no
  user action, and a second phone on the JVM saw it.
- For the maintainer: the privacy wording is approved (UiCopy.PRIVACY_FRIENDS, docs/privacy.md,
  ADR 0004); still ask Light whether a privacy statement is needed.
- Deferred from PR 4: "Play from here" (F7) from a Puzzle's start (R4.15: its own entry on the
  puzzle screen, a decided-Position test, "Ends your current game"); a draw offer while the computer
  thinks (R4.5: on the user's Move only); exact replay at Level 8 (clock-bound). Not measured: the
  strength effect of clearing the engine's table before each Move at Levels 1-7 (R4.8), so a rerun
  of LevelCalibrationTest with the same clearing would confirm the Level gaps.
- Parked (2026-09-28): a standalone CC0 Polyglot book repo. A CC0 book already exists (the jja
  books, CC0 by their author, built from the Lichess database; no checksums, 50 KB-339 MB). Ours
  would add a small reproducible book with a published checksum, but its builder compiles the rules
  core and Book reader, so publishing it means relicensing those to MIT. Revisit only if asked.
- Slated for a follow-up, not scheduled (maintainer, 2026-09-28):
  - "Play a stranger", v3.x after friend play ships. It is anonymous matchmaking with no accounts
    and no visible lobby: the Relay's matchmaker hands a waiting ticket to the next phone that asks,
    and both get seats as if a code were redeemed. It reopens C6 ("friends only"), so it gets an
    ADR. Open: ticket expiry (about 7 days), matching on Days per Move only, an optional coarse
    strength band, one ticket per phone, and the brief IP rate limit. No chat keeps harassment out;
    timeouts handle stalling.
  - LP3-only play can only be an honour rule, because the Relay can't tell an LP3 from a sideloaded
    APK.
- Working rules learned:
  - Builders run with `isolation: worktree`. The orchestrator must not EnterWorktree while a
    non-isolated agent works in its worktree: that moves the harness pin and blocks the agent.
  - 1Password signing sometimes needs the maintainer at Touch ID: on "agent returned an error",
    leave work staged and ask; never commit unsigned.
  - Long gradle and light-build runs go in the background (the maintainer backgrounds long blocking
    commands).
  - The LP3's serial is in the umbrella PLATFORM.md (never in this public repo). Take the lease with
    noclobber and message the Reader session before and after (Doom is finished, 2026-09-28). The
    chess emulator is LightPhone3-chess on emulator-5556; never touch 5554.
- Waiting on the maintainer (none blocks development):
  - Light: a GPLv3 Tool (and GPL alongside the SDK's proprietary ML Kit); production push; an
    alert/badge method; a privacy statement; listing requirements; the SDK's 1 s splash; and the
    scanner's bare-`javaClass` gap (light-sdk).
  - The submission in Light's portal: v0.1.0 is safe; hold v0.2.0 and later for the licence answer.
  - Maybe: release the CC0 opening Book as its own repo (builder MIT, book.bin CC0) after checking
    whether a CC0 Polyglot book already exists.
  - Stockfish: installed (Homebrew, 19) and run on 2026-09-29; the UCI_Elo fit was not consistent,
    so still no Elo labels (decision log "Stockfish calibration (2026-09-29)"). Next try, if wanted:
    a 3M-node gauntlet, about 15 hours of the Mac with 8 threads.

## Where things are
- Design: `CONTEXT.md` (glossary), `docs/adr/0001-0004`, `docs/design/decision-log.md`. The log
  holds every ruling and each contradiction it resolved; read it before reopening a decision.
- Repo: the light-reader template (`AGENTS.md`, `mise.toml`, `scripts/ci.sh`, `light-build.sh`,
  `emulator-build.sh`, `domain-drift.sh`), light-sdk submodule at v0.1.2, `tool/lighttool.toml`
  (com.yarosz.chess, portrait, serverPackage com.lightos, INTERNET only for the Relay: W1).
- Rules core: `tool/src/main/kotlin/com/yarosz/chess/rules/` (pure Kotlin). Perft exact on the start
  position, Kiwipete and positions 3-6; property tests over 400 random Games. `-Dperft.deep=true`
  adds Kiwipete d5 and position 6 d5.
- Board: `tool/src/main/kotlin/com/yarosz/chess/board/`. `PositionView` (Compose Canvas),
  `MoveInput` (pure input state machine), `Review` (wheel), `Shades`/`Marks` (constants,
  `ShadesTest` enforces D10), `Strip`, and `PieceVectors`, generated by `scripts/build-pieces.py`
  from `art/pieces/geometric` and `art/pieces/rounded` (original drawings, CC0; the Piece Set picks
  one; CI runs `--check`). `DESIGN.md` holds the values, the marker readability rule and the input
  decisions; copy lives in `UiCopy`.
- Puzzle flow: `puzzles/Attempt.kt` (pure state machine: A3-A6), `PuzzleFlow.kt` (scoring,
  selection, Missed, Reset rating, the F1 carry-over), `Glicko2.kt` (checked against Glickman's
  worked example), `PuzzleData`/`PuzzleStore` (`puzzles.json`: temp + rename, `.bak`, lenient read).
  `PuzzleOwner` is the process-wide owner (clock, file, keep-screen-on); `PuzzleScreen` and
  `MenuScreen` are views onto it (`ChessScreen` split into `PuzzleScreen` and `GameScreen` under
  Navigation D, N1-N10). DESIGN.md "The puzzle flow" lists the choices the log doesn't make.
- Pack: `scripts/build-pack.py` builds `tool/src/main/assets/pack/` (25 Band files + manifest.json,
  46,971 Puzzles, 6.5 MB) from the pinned 2026-09-09 dump; `docs/pack.md` says how and why. Bands
  the A1 filter leaves short are filled from R1.4's filter (13,264 Puzzles, counted per Band in the
  manifest): a ruling for the next product review. Loader: `tool/src/main/kotlin/com/yarosz/chess/
  puzzles/` (`Pack`, `Puzzle`); `CommittedPackTest` parses every Puzzle with the rules core.
- Engine spikes: `spikes/pirarucu` (viable, ~3050 CCRL, 1.85M nps on a Mac core) and
  `spikes/karballo` (viable, ~400 Elo weaker, 1.5/20 head-to-head). Pirarucu is the v2 engine;
  Karballo is the fallback for a fully MIT Tool.
- Release: `RELEASING.md` (checklist), `scripts/release-check.sh` (`scan`, `apk`, `run`,
  `upgrade REF`) driving the emulator through `scripts/release-drive.py`, `docs/release-notes/`.
  The scan takes private patterns from a file outside the repo (`RELEASE_SCAN_PRIVATE`). About's
  copy is `UiCopy.about`; NOTICE carries the same library list.

- Engine (feat/v2-engine): `tool/src/main/kotlin/vendor/pirarucu/` from `scripts/vendor-pirarucu.py`
  (`--check`, `--parity`; provenance and changes in `vendor/pirarucu/README.md`); `engine/` holds
  `Engine`, `PirarucuEngine` and `EngineHost.shared`. `ParityTest` pins upstream's fixed-depth node
  counts. Benchmark: `src/benchmark/.../bench/` (the `benchmark` build type only), `mise run bench
  <serial>` (`-d`: debuggable twin), `mise run bench-jvm` (Mac: ~1.9M nps, depth 14 in 195 ms P50),
  `scripts/bench-test.sh`. LP3 (non-debuggable): 558K nps P50 / 862K P90, depth 14 in 693 / 1,151
  ms, 3 s reaches depth 18 / 22, A78 cores 6-7, heap limit 128 MB, thermal 0; 3.6x the debuggable
  build. Facts and choices: decision log, "v2 PR 1" and "v2 PR 2".
- Game record (v2 PR 5 core, feat/v2-record): `tool/src/main/kotlin/com/yarosz/chess/games/`.
  `GameRecord` (Game + Side, Level, ThinkTime, Takebacks, Game Hints), `Pgn` (writer + reader; tags
  and comment commands in its KDoc), `GameData`/`GameStore` (`games.json`: the Game in progress as
  PGN + FEN checkpoint, the last 50 finished). Shared save pattern in `SaveFile` (PuzzleStore uses
  it too). The game screen (feat/v2-game) wires it; the record also keeps the Game's seed and the
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
  used), `GameStrip` (the strip by context; StripFitTest), `GameScreen`, `MenuScreen` (game Menu,
  new-game page, Games, Moves), `GameReviewScreen` (a finished Game). DESIGN.md "The game screen";
  decision log "v2 PR 4".
- Levels (feat/v2-levels): `engine/Level.kt` (Level -> node budget, depth cap, N, margin;
  ThinkTime), `engine/LevelPlayer.kt` (the pick, seeded per Game and Ply; MoveNow;
  `LevelMove.trueScore` for G1), `EngineHost.play`. Calibration: `LevelCalibrationTest`
  (`-Dcalibrate=blunders,random,ladder,anchor`), `scripts/level-elo.py`, Karballo line server
  `spikes/karballo/flat/serve`. Values, tables and method: `docs/levels.md` and decision log
  "v2 PR 3". No Elo labels: neither Karballo nor Stockfish's UCI_Elo (`-Dcalibrate=stockfish`,
  decision log "Stockfish calibration (2026-09-29)") gives a consistent fit.
- Licence: GPL-3.0-or-later, relicensable later. Outside code needs a copyright assignment
  (CONTRIBUTING.md).
- Emulator: AVD `LightPhone3-chess` on emulator-5556 (`scripts/emulator/RECIPE.md`). Scripts find it
  by AVD name (`scripts/chess-emu.sh`, `CHESS_AVD`). Never use emulator-5554, which belongs to the
  Reader. On a fresh boot, dismiss the ImmersiveModeConfirmation dialog
  (`mise run ui tap "GOT IT"`). The LP3 is shared through the lease in the umbrella PLATFORM.md.
- Correspondence client (v3 PR 1, feat/v3-client): `tool/src/main/kotlin/com/yarosz/chess/relay/`
  and `correspondence/`; tests in the same packages under `tool/src/test/` (`FakeRelay`,
  `FlakyTransport`, `Phones.kt`). Decision log "v3 PR 1".
- Relay (v3): `docs/protocol.md` v1.0 is the wire contract; `relay/` is the Worker +
  `CorrespondenceGame` Durable Object (`cd relay && npm test`, Node >= 22), deployed and live at the
  W11 URL (relay/README.md "Deploying").

## Next
The order is STATUS's (0.4.0, then v3 PR 3). Also open, unscheduled:
1. On the LP3: a photo of the shades through the grayscale filter (D10), and `signoff/lp3` in
   `ci.sh`. Time to the first Puzzle on resume was about 1,035 ms (2026-09-28, debug build), just
   after LightActivity's 1 s splash (target <= 1.5 s, decision log "v1 PR 4 rulings").
2. For the next product review: with RD 500, volatility 0.09 and one Puzzle per rating period, the
   deviation settles near 73-74 (about 48 Puzzles to lose the "?"), so the 45 floor is never
   reached.
3. v2.x: "Play from here" (F7, decision log R4.15).
4. Licence: Light's SDK bundles Google's proprietary ML Kit barcode library (~20 MB). With GPLv3
   Pirarucu in the same APK this is a compatibility question for Light (the maintainer asks). Our
   own code can carry a linking exception; Pirarucu's can't. Karballo (MIT) stays the fallback.

## Open outside questions (none blocks development)
- Light: will they sign a GPLv3 Tool; is production push live for Tools; is an alert or badge method
  planned; is a privacy statement needed; what does a listing need?
- light-sdk: the reflection scan misses a bare `javaClass` call (`LightSdkPlugin.kt:119`). Under
  Light's AI policy, the maintainer must report it personally.
- light-sdk: every Tool's APK gets INTERNET, CAMERA and six more permissions merged in from the
  SDK's libraries (OkHttp, Google datatransport, WorkManager, Media3, CameraX), whatever
  `lighttool.toml` declares. So About says "Chess never uses the network" rather than "No network
  permission" (D5 holds for what the Tool declares). The SDK also bundles Google's proprietary ML
  Kit barcode binary (`libbarhopper_v3.so`, about 20 MB of the 28 MB APK across four ABIs): part of
  Light's GPL question.
