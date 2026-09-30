# Ledger

STATUS: v1 0.1.0 is on main (the squashed v1 release with the smoke fixes S1-S3, then #4 the CC0
pieces P1, #5 the review follow-ups V1-V5, #6 the Piece Set chooser P2), pending LP3 photos for the
README and the maintainer's submission to Light. v2: Pirarucu is GO (ADR 0001 accepted: LP3
non-debuggable 558K nps P50, depth 18 in 3 s). feat/v2-play merges feat/v1-release and feat/v2-bench
as v2's base, and also the Levels (PR 3), the game record (PR 5 core), the Book (PR 6) and the game
screen (PR 4, with R4.16/R4.17), reviewed and checked on the LP3: the computer is playable at every
Level. v0.2.0 (v1 + v2) is released from main. v3: the Relay is done (protocol 1.0) and has its
address, `https://chess-relay.yarosz.com` (decision log W11), deployed and live since 2026-09-29; the
client and Play a friend are on main (#3), and so is the Captured Pieces row (P3, #10). v0.3.0 (v1 +
v2 + v3 + P3) is released. v0.3.1 is the open release PR, carrying the v3 review follow-ups: the
Relay's limits and plain-HTTP refusal (#13, L1-L5) and Play a friend's Time Left and deleted Game
(#14, W12, W13). Next: v3 PR 3 (the live WebSocket).
HANDOFF (Puzzles page and Pieces, feat/puzzles-page from main 65eb451, 2026-09-30): the wheel on a
board (N20, owner-reported on the LP3: scrubbing the Moves changed the brightness). `Review.wheel`
now takes every turn on a board, at either end and before any Move, and still leaves the click on
the live Position to LightOS; `PuzzleViewModel` no longer hands LightOS the turns of the held start.
Pages that aren't boards are unchanged (F3). To check on the LP3: a fast scrub back to the present
leaves the brightness alone, and the click on the live Position still toggles the flashlight.
HANDOFF (Navigation D, feat/navigation from main 04ae74a, 2026-09-30): the owner's navigation model,
decision log "Navigation D" N1-N10, DESIGN.md "Navigation". `HomeScreen` is the root (the
`@InitialScreen`; `ChessScreen` is gone, split into `PuzzleScreen` and `GameScreen`), and the place in
`mode.txt` is pushed over it in `willShow` on the activity's first show. `FriendListScreen` is the Play
a friend list (top bar, no Menu). `Strip` spans the screen and takes `back` and `menu`: LightOS's
arrow at its top-bar place and the three-square Menu mark, centred on the font's x-height. Menus hold
only their board's actions (`GameMenu`, `FriendMenu`); Pieces and the Puzzle id moved to Home and
About. Pure parts and their tests: `HomeRows`/`Navigation` (HomeTest), `GameMenu`/`FriendMenu`/strip
flags (MenuTest), `PuzzleStrip` (StripFitTest now builds every puzzle strip from it and measures all
strips with the arrow and the mark). `release-drive.py` reaches the rating and About through Home (or
an older build's Menu, for `upgrade`). Checked on the emulator (geometry, not glyphs: it draws
Roboto). To check on the LP3: the arrow's ink at pixels 48-82 and the mark's x-height centring in
Akkurat, "Rematch? · Accept · Decline" on one line with the mark, Home's wheel scroll, and whether
Home's first view (About and half of Pieces below the fold) is acceptable (the owner's call; row
sizes unchanged). Review follow-ups (PR #16): `release-drive.py about` scrolls Home to About
(uiautomator omits off-screen rows); leaving the board drops a Game Hint being found
(`GameOwner.pause`); `MenuScreen` takes `overGame` from its opener, so New game from Home never
starts a search; the arrow's target is 48 dp (`StripLayout.backTarget`); `HomeNavigator` holds
Home's back-stack wiring (HomeTest); `GameOwnerTest` drives the owner on the JVM with a held
engine. Left: `Rows.HEIGHT` (48 dp) is the wheel step while Home's and the pages' rows measure
about 53 dp, so a detent scrolls slightly under a row (predates Navigation D).
HANDOFF (0.3.1 release PR, release/0.3.1 from main b7e4caa, 2026-09-29): versionName 0.3.1,
versionCode 4, `UiCopy.VERSION` with it; notes in `docs/release-notes/0.3.1.md` (the Pack is
unchanged). The Relay at b7e4caa is deployed (version 23b28619): `/health` over HTTPS answers ok,
plain HTTP answers `426 upgrade_required` with no `Location`, and `RelayEndToEndTest` passes against
the live URL. Cloudflare's edge drops the Worker's `Upgrade` and `Connection` headers (hop-by-hop),
so the live 426 carries neither; the error body says to use HTTPS, which is what clients read.
README's status line names 0.3.1. The release checks (scan on a fresh public clone, relay and
`/health`, apk, run, upgrade v0.3.0, light-build) ran on this branch. Next: merge, tag v0.3.1 with a notes-only GitHub Release; the Light submission is the maintainer's.
HANDOFF (0.3.0 release PR, release/0.3.0 from main bb8da79, 2026-09-29): versionName 0.3.0,
versionCode 3, `UiCopy.VERSION` with it; notes in `docs/release-notes/0.3.0.md` (the Pack is unchanged
since v0.2.0, so the notes say nothing of it). README's status line names v3 done. Two doc nits from
#10: DESIGN.md's strip bullet split into sentences, the decision log's P3 "What counts" rewrapped.
`release-check.sh run` expected v1's privacy line; it now expects the one the build shows (v3's
while `RelayConfig.URL` is set), and `release-drive.py about` scrolls About, since v3's longer line
pushes the Puzzles line below the fold. The release checks (scan on a fresh public clone, relay and
`/health`, apk, run, upgrade v0.2.0, light-build) ran on this branch's HEAD. Next: merge, tag v0.3.0 with a notes-only GitHub Release;
the Light submission of that hash is the maintainer's.
HANDOFF (v3 Relay URL, v3/relay-url from feat/v3, 2026-09-29): main after 0.2.0 (585d8d6, a4706da, #7,
#8, #9) merged into feat/v3's tip (a merge commit, no rewrite). main's v2 is a squash (#2), so the
conflicts were resolved against 359219b, the v2 tip feat/v3 had merged: only main's later work (README
for v2, M4, the About Book line, the Stockfish calibration, 0.2.0, the scan's 10.0.2.2/10.0.2.3) had
to be combined with v3. Kept main's versionName 0.2.0 / versionCode 2 (v3 had no bump of its own;
the v3 release PR bumps it). v3's forward-merge rulings M4/M5 are now M5/M6 (main's M4 came first);
M7: the friend screens read M4's early Piece Set too. The Relay's URL (W11): `RelayConfig.URL` is
`https://chess-relay.yarosz.com`, a custom domain on the maintainer's zone bound by the maintainer's
infrastructure code, not wrangler (no `routes`); `workers_dev` off (relay/wrangler.jsonc,
relay/README.md "Deploying"). A debug build still takes `-Prelay.url` (the emulator's local Relay);
without it a debug build, like release, talks to that URL. README's Privacy paragraph still says the
Relay isn't deployed: the v3 release PR changes it with the deploy. Next: the maintainer deploys
(`npx wrangler deploy` in relay/, then binds the domain, then `/health` at the URL), then the v3
release PR (version bump, notes, README privacy, LP3 checks).
HANDOFF (forward merge, 2026-09-29): main (v1 0.1.0 + #4-#6) is merged into feat/v2-play, and
feat/v2-play into feat/v3 (merge commits, no rewrite). main's v1 is a squash, so the first merge's
content was resolved against fix/v1-keyup (25530a1), the commit v1's squash starts from. Decision
log "Forward merge" M1-M3 and M5-M6: the Piece Set stays in `puzzles.json` and every board draws it
(puzzle, game, Games Review, a Correspondence Game, each promotion picker); "Pieces" sits just above
About in every Menu (puzzle, game, Play a friend, a Correspondence Game's); New game, Games and Moves
are their own Menu screens (S3); the game owner takes V4. Checked on the emulator: the Menus, a
Puzzle and a Game in the rounded set; not Play a friend (the emulator build has no Relay URL). To
re-check on the LP3: the rounded set on a Correspondence Game. (M6, the Correspondence Game's Menu
keeping Moves and Rename inside one screen, is fixed by N6 in Navigation D.)
HANDOFF (0.2.0 release PR, release/0.2.0 from main, 2026-09-29): versionName 0.2.0, versionCode 2,
`UiCopy.VERSION` with it; notes in `docs/release-notes/0.2.0.md` (the Pack is unchanged since v0.1.0,
only the Book is new, so the notes say nothing of the Pack). `release-check.sh scan` now allows the
emulator's fixed 10.0.2.2 and 10.0.2.3, as feat/v3 needs, and passes on the public repo. The release
checks (scan, apk, run, upgrade v0.1.0, light-build) ran on this branch's HEAD. Next: merge, tag
v0.2.0 with a notes-only GitHub Release; the Light submission of that hash is the maintainer's.
HANDOFF (v2 LP3 checks, 2026-09-29): checked under the lease on main (b823cc0): the Menu has Play
the computer, then Pieces just above About, then the Puzzle id row; the rounded set draws on the
game board; a cold start into a Game shows a black frame, then its first board in the rounded set
(M4); system Back from New game, Moves and Games returns to the Menu (S3, M2). The 0.2.0 notes don't
list the two piece sets: they are already in v0.1.0.
HANDOFF (v1 pre-submission LP3 checks, 2026-09-29): checked under the lease on main (ecd6349): both
Piece Sets draw on the LP3; the Menu's Pieces row switches sets and the choice survives a relaunch;
the Puzzle id row is the Menu's last row; Back from About returns to the Menu (S3); the wheel scrolls
back into Review and its click returns to the latest Move (R4.17). v0.1.0 tagged at ecd6349 with a
notes-only GitHub Release, after the release checks (scan, apk, run, light-build). The Light
submission of that hash is the maintainer's. Noted: main's `release-check.sh scan` history check will
need v3's `10.0.2.2` allowance (and check `10.0.2.3`) once feat/v3 merges -- its "Play a friend"
commits already carry the emulator host alias, which main's version of the script doesn't yet allow.
Still open on the LP3, from main's v1 work: how the greys and both sets look on the physical panel
(maintainer's eyes/photos) -- the rounded set's king/queen likeness, the white bishop's slot and the
knight's ear at 1x, and the geometric set's white outer line on light squares, the knight's eye and
the bishop's slot; a reply slide doesn't replay after Menu and back, Hint taps during a reply don't
delay it, and a Missed replay opens at once.
HANDOFF (PR 2 review fixes, fix/v2-review from feat/v2, 2026-09-29): the README describes v2 (the
computer, its credits, privacy); About's Book line reads "the Lichess games database", as NOTICE;
`ToolMetadataTest` checks the engine and Book credits in About, NOTICE and the README; decision log
M4: the Piece Set is read from `puzzles.json` before any Band, so a cold start into a Game draws the
chosen set from its first frame and the game Menu has the Pieces row at once. DESIGN.md rewrapped and
says what the wheel does on the game Menu. Checked on the emulator; no version bump (the release PR
does that). To check on the LP3: a cold start into a Game in the rounded set.
HANDOFF (Relay live, 2026-09-29): the Worker `chess-relay` is deployed with wrangler from this branch
(`relay/`, workers_dev off, no routes); `chess-relay.yarosz.com` is bound to it by the maintainer's
infrastructure code (W11). `/health` answers `{"status":"ok","protocol":"1.0","majors":[1]}`.
`RelayEndToEndTest` passes against the live URL. On devices: the LP3 created an Invite Code, the
emulator joined it, and 1.e4 (LP3) and 1...e5 (emulator) each reached the other within one 60 s poll.
FOLLOW-UPS (v3 review, 2026-09-29), none blocking: `localhost` is accepted by `RelayConfig.allowed`
but not by the debug network security config; a cold-started `friend-send` job REPLACEs itself once
(FriendOwner init). Optional: Always Use HTTPS/HSTS on the zone (the Worker itself now refuses
plain HTTP, L3), the maintainer's choice.
Fixed in L1-L5 (a Relay deploy, relay/README.md, puts them live): the limits key on the IPv6
/64, plus 100/60 s per /48 on redeem and create (L5, two new bindings), `POST /games` is limited
(10/60 s), plain HTTP gets 426 with `Upgrade: TLS/1.0, HTTP/1.1`, `parseSync` shape-checks
seatSecret. After deploying, check `curl -si http://chess-relay.yarosz.com/health`: a 426 with an
`Upgrade` header and no `Location`.
LAST SESSION: 2026-09-30

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
  - feat/level-calibration (on main): the Stockfish mode of LevelCalibrationTest and its
    gauntlet (docs/levels.md "The Stockfish gauntlet"). Test code, script and docs only; no labels,
    because the fit was not consistent. 289 tests.
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
- feat/v3-client (from feat/v2-play, merges feat/v3-relay): v3 PR 1, the correspondence client core.
  365 JVM tests (4 skipped: calibration, and the opt-in end-to-end test), relay 93. Rulings: decision
  log "v3 PR 1" (V1-V15). `relay/` protocol types + `RelayClient` over a `RelayTransport` seam
  (`OkHttpTransport`, OkHttp already in the SDK's dependencies); `correspondence/` `GameLog` (every
  entry checked by the rules core), `Correspondence` (invites, entries, conflicts, rematch, `syncAll`
  for the LightWork job), `CorrespondenceStore` (`no_backup/correspondence.json`). The rules core
  gained `TimeoutClaim` / `WinReason.TIME`. Tests: a fake Relay mirroring relay/src, error-code tables,
  a two-phone property test in bad weather (`-Dcorrespondence.seeds=`), and `RelayEndToEndTest`
  against `wrangler dev --local` (`-Drelay.e2e=`; run once, green). Nothing in the Tool constructs
  the client (`NoNetworkYetTest`).
- feat/v3-play (from feat/v3-client): v3 PR 2, Play a friend: the screens and the network. Rulings:
  decision log "v3 PR 2 (expert rulings)" (W1-W10) and "v3 PR 2 (implementation)" (Y1-Y14); the
  screens: DESIGN.md "Play a friend". 392 JVM tests (5 skipped: calibration and the opt-in e2e and
  second-phone tests), relay 101. Built:
  - W9 in protocol 1.0: the phone chooses its seat secret on redeem and join (docs/protocol.md,
    relay/, the client, FakeRelay), saved first as a `PendingSeat` and sent again until answered.
  - W8: `RelayConfig.URL`, committed empty (Play a friend doesn't exist then); `-Prelay.url` for a
    debug build only (its BuildConfig), cleartext to 10.0.2.2/127.0.0.1 in debug only;
    `scripts/release-check.sh relay` refuses a release declaring INTERNET with no URL.
  - W1: ADR 0004, lighttool.toml declares INTERNET, the privacy line once the URL is set, BoundaryTest
    in place of NoNetworkYetTest, FriendOwnerTest's empty-store and empty-URL tests.
  - W4/W6/W5/W2/W10: `FriendOwner`, the Play a friend page, New game + invite, Enter code (LP3
    keyboard), the board with Send/Undo, the game Menu (Send and offer draw, Offer draw, Resign, Moves,
    Rename, Forget game), Rematch, finished Games in the Games list, mode.txt `FRIEND`.
  - W7: LightWork `friend-sync` (hourly, only while a Game waits on the opponent) and `friend-send`
    (one-off, Retry); syncAll when the Tool opens and when a friend screen shows.
  - Checked on the emulator against `wrangler dev --local` and a JVM second phone
    (`SecondPhoneTest`, `-Drelay.phone=`): create code, enter code, Moves both ways, Send/Undo, a
    declined draw offer, Send and offer draw, the Worker stopped mid-send (Not sent, then Retry), a
    force-stop with a Pending Entry then relaunch (sent on open), the one-minute board sync, Resign,
    Rematch accepted, the Result in Games, "Not a code" / "No such code".
- Next: v3 PR 3, the live WebSocket (`/live`, G3: ping every 5 s while the board shows, closed in
  onAppPause, "Live · Your move" / "Live · Their move"), which replaces Y12's one-minute board sync.
  Done on the LP3 (2026-09-29): LightOS disables Doze (PLATFORM.md). A one-off friend-send job
  delivered a Move that had failed to send once a local Relay was back (via adb reverse), with no
  user action, and a second phone on the JVM saw it.
- For the maintainer (PR 2):
  - Done 2026-09-28: the privacy wording is approved (UiCopy.PRIVACY_FRIENDS, docs/privacy.md, ADR
    0004). Still ask Light whether a privacy statement is needed.
  - A deploy of the Relay (relay/README.md) at `https://chess-relay.yarosz.com`, the URL
    `RelayConfig.URL` now has (W11, v3/relay-url). Until it answers `/health`, a release would show
    Play a friend with nothing behind it.
- Deferred from PR 4: "Play from here" (F7) from a Puzzle's start (R4.15: its own entry on the puzzle
  screen, a decided-Position test, "Ends your current game"); a draw offer while the computer thinks
  (R4.5: on the user's Move only); exact replay at Level 8 (clock-bound). Not measured: the strength
  effect of clearing the engine's table before each Move at Levels 1-7 (R4.8), so a rerun of
  LevelCalibrationTest with the same clearing would confirm the Level gaps.
- Parked (2026-09-28): a standalone CC0 Polyglot book repo. A CC0 book already exists (the jja
  books, CC0 by their author, built from the Lichess database; no checksums, 50 KB-339 MB). Ours
  would add a small reproducible book with a published checksum, but its builder compiles the rules
  core and Book reader, so publishing it means relicensing those to MIT. Revisit only if asked.
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
    the Reader session before and after (Doom is finished, 2026-09-28). The chess emulator is LightPhone3-chess on
    emulator-5556; never touch 5554.
- Waiting on the maintainer (none blocks development):
  - Light: a GPLv3 Tool (and GPL alongside the SDK's proprietary ML Kit); production push; an
    alert/badge method; a privacy statement; listing requirements; the SDK's 1 s splash; and the
    scanner's bare-`javaClass` gap (light-sdk).
  - The Relay's deploy from the maintainer's Cloudflare account (relay/README.md); the URL is chosen
    (W11).
  - LP3 photos for the README, then the v1 submission.
  - Maybe: release the CC0 opening Book as its own repo (builder MIT, book.bin CC0) after checking
    whether a CC0 Polyglot book already exists.
  - Stockfish: installed (Homebrew, 19) and run on 2026-09-29; the UCI_Elo fit was not consistent,
    so still no Elo labels (decision log "Stockfish calibration (2026-09-29)"). Next try, if wanted: a
    3M-node gauntlet, about 15 hours of the Mac with 8 threads.

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
  D10), `Strip`, and `PieceVectors`, generated by `scripts/build-pieces.py` from
  `art/pieces/geometric` and `art/pieces/rounded` (original drawings, CC0; the Piece Set picks one;
  CI runs `--check`). `DESIGN.md` holds the values, the marker
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
  `docs/levels.md` and decision log "v2 PR 3". No Elo labels: neither Karballo nor Stockfish's UCI_Elo
  (`-Dcalibrate=stockfish`, decision log "Stockfish calibration (2026-09-29)") gives a consistent fit.
- Licence: GPL-3.0-or-later, relicensable later. Outside code needs a copyright assignment
  (CONTRIBUTING.md).
- Emulator: AVD `LightPhone3-chess` on emulator-5556 (`scripts/emulator/RECIPE.md`). Scripts find it by
  AVD name (`scripts/chess-emu.sh`, `CHESS_AVD`). Never use emulator-5554, which belongs to the Reader.
  On a fresh boot, dismiss the ImmersiveModeConfirmation dialog (`mise run ui tap "GOT IT"`). The LP3 is
  shared through `~/.cache/lp3-lease`; the protocol is in the umbrella PLATFORM.md.
- Correspondence client (v3 PR 1, feat/v3-client): `tool/src/main/kotlin/com/yarosz/chess/relay/`
  and `correspondence/`; tests in the same packages under `tool/src/test/` (`FakeRelay`,
  `FlakyTransport`, `Phones.kt`). Decision log "v3 PR 1".
- Relay (v3, branch feat/v3-relay): `docs/protocol.md` v1.0 is the wire contract; `relay/` is the
  Worker + `CorrespondenceGame` Durable Object, tested locally only (`cd relay && npm test`, Node
  >= 22). NOT deployed: deploying needs the maintainer's account choice (`relay/README.md`).

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
