# Settled decisions (running log; newest round last)

## Round 1 (expert rulings, 2026-09-27)
- R1.1 Rules core: our own pure-Kotlin core, built for correctness and a clean domain model, not
  speed. Immutable Position values; legal movegen, SAN/FEN, repetition, 50-move rule, insufficient
  material, stalemate. Perft (startpos, Kiwipete, positions 3-6) to depth 5. The engine talks to it only
  through FEN + UCI strings, and the core validates every engine move.
- R1.2 Engine: vendor Pirarucu `pirarucu-common` (Kotlin, GPLv3, CCRL Blitz ~3058 1 CPU, 6,067 lines,
  one expect object, no reflection, unmaintained since 2020). Flatten per PLATFORM.md; single-thread
  MainSearch on a process-wide dispatcher; add our own difficulty levels (Pirarucu has none). Measure
  nps and time-to-depth on the LP3 before committing. CuckooChess rejected: Java, uses reflection via
  getResourceAsStream, uses threads, rated 2651. Check whether `SearchOptions.stop` needs @Volatile.
- R1.3 Licence: GPLv3-or-later; NOTICE credits for Pirarucu, cburnett and Lichess. OPEN: the maintainer asks
  Light whether a GPLv3 Tool is acceptable.
- R1.4 Puzzles: Lichess CC0 (6,100,952 puzzles; csv.zst 304 MB, 2026-09-09). Filter Popularity >= 85,
  NbPlays >= 500, RD <= 90; stratify by rating and theme; 100K-200K puzzles; fixed binary layout at
  ~40-60 B/puzzle; one .bin per rating band, each < 5 MB. Reproducible script, pinned dump date, dump not
  committed. FEN is the position before the opponent's move, Moves[0] is the opponent's, and the user
  plays the odd plies. Keep PuzzleId.
- R1.5 Progression: Glicko-2 (1500/350) serving unseen puzzles within about ±100 of the user's rating,
  plus one theme filter (Mate / Endgame / Any). Theme packs in v1.x.
- R1.6 Input: tap-tap primary (dots on legal targets, rings on captures; reselect/deselect rules),
  plus drag with the lifted piece drawn one square above the finger; four-piece promotion picker.
- R1.7 Rendering: cburnett (Wikimedia BSD-3 / GPLv2+ option), converted to ImageVector at build time.
  Tune shades on the LP3 (start: #D8D8D8 / #8C8C8C). Last move = shade + outline, check = ring,
  selection = heavy border, coordinates inside the edge squares. Exclude staunty and maestro (NC).
- R1.8 Layout: 312 dp board (39 dp = 117 px squares) plus a 48 dp strip: status + up to 3 text buttons.
- R1.9 Keys: the wheel browses history in review AND mid-game (a "live" tap returns); otherwise return
  false. Volume and shutter stay with LightOS.
- R1.10 Identity: "Chess", com.yarosz.chess, github.com/yarosz/light-chess, light-reader template;
  vendor/pirarucu + scripts/vendor.py modelled on light-doom's.
- R1.11 Game model: Game = startFen + List<GameEvent> (Move(uci), Resign, DrawOffer/Accept, later Flag
  and clock stamps); all other state is derived by replay; both phones compare our own position digest
  over a canonical FEN (not the engine's Zobrist).

## Branch C: v3 network (expert rulings, 2026-09-27)
Facts found (SDK source): F1 remote push exists as a silent UnifiedPush wake (enablePushNotifications,
onPushNotification); it is unproven on production LightOS, and a Tool CANNOT alert the user
(android.app blocked, no notification shade, no badge method). F2 LightWork (WorkManager) gives
one-off and periodic background jobs (15 min minimum). F3 okhttp and ktor are in ALLOWED_DEPENDENCIES
(also BouncyCastle, tweetnacl). F4 DO free tier: 100k req/day, 5 GB; WebSocket hibernation. F5 Lichess
Board API needs accounts and OAuth and has no blitz.
- C1 Transport: Cloudflare Worker + one SQLite-backed Durable Object per game. Main path is HTTPS
  request/response (POST move, GET since ply). WSS via OkHttp + Hibernation only while both are on
  screen. Lichess and SMS rejected as the v3 transport.
- C2 Correspondence first: 3 days/move default (1/3/7). The relay stamps serverTime, and timeouts are
  computed from those stamps. "Live" = the same game with both sockets open. No alerts possible;
  LightWork periodic sync every 1-2 h only while waiting on the opponent; the home screen shows
  "Your move: N games".
- C3 Invite codes: 8-char Crockford base32, single use, 48 h expiry; the creator picks colour and time
  control; a per-seat random 256-bit bearer secret in filesDir; the relay stores its SHA-256.
- C4 Protocol: append-only log with CAS on ply; bearer auth, no signatures. Entry {v, gameId, ply,
  kind(move|resign|drawOffer|drawAccept|drawDecline|claim), uci?, hash=SHA-256(FEN fields 1-4)}.
  Idempotent re-append; serverTime; GET ?since=ply.
- C5 Disagreement freezes the game ("Out of sync"); results are derived by both clients; the relay
  closes the log after resign / drawAccept by kind alone.
- C6 Privacy: invites 48 h; a DO alarm deletes 30 days after the last entry; Workers Logs off; no
  chat; friends only, no ratings.
- C7 Ops: free tier holds only if polling is kept small; relay/ in repo, wrangler, /health.
- C8 Offline: write the pending entry to filesDir first, show "waiting to send", send via a LightWork
  job with Retry; one pending entry per game; roll back on refusal.
OPEN (maintainer → Light): production push for third-party Tools? any alert/badge method planned? privacy
statement needed for a Tool that talks to its author's server?

## Branch B: v2 engine (expert rulings, 2026-09-27; agrees with R1.2)
Facts: Pirarucu 3.3.5 CCRL 40/15 1 CPU = 3049; common code has no threads or reflection; no book,
node limit or strength limiter; stop is a plain var. CuckooChess 2577 (40/15). Chess22k 3117 (GPLv3,
Java, lazy SMP). Karballo = MIT Kotlin port of Carballo, ~2729 (40/2), Elo limiter 500-2100, Kotlin
1.2, last commit 2018. Belief: 0.3-1 Mnps on one A78 core under ART, 3x less on an A55; Pirarucu at
3 s/move ≈ 2700 CCRL-scale.
- B1 Vendor Pirarucu behind a narrow interface (FEN + moves in, best move + info out); keep our own
  core; drop UCI/tuning/MT code; make `stop` @Volatile; update Kotlin 1.3 → 2.3.20. Fallbacks: Karballo
  (MIT, if the licence must be non-GPL), then Chess22k via J2K.
- B2 Eight named levels; weakening lives in our wrapper: an added node limit (device-independent) plus
  a MultiPV-style pick among the top N root moves within a centipawn margin that tightens by level.
  Level 8 = full strength. Elo labels ("approx.") only after Mac calibration against Stockfish UCI_Elo
  or Karballo.
- B3 Level 8 ≤ 3 s: a node budget capped by wall time; thinking mark; input locked. LP3 nps benchmark
  in week 1, noting whether the thread lands on an A78 or an A55.
- B4 Our own small Polyglot book (< 1 MB, documented provenance; Karballo's MIT reader reusable);
  not Fruit's book_small.bin; CuckooChess book.txt usable as seed lines if GPL. Off at the lowest levels.
- B5 Colour choice, takeback always (counted in PGN), resign with confirm, the engine accepts a draw
  offer only at eval <= -50 cp and past move 30, automatic draw rules in our core, hint = level-8 best
  move (counted). No clocks. One game in progress, saved and resumed.
- B6 No pondering; stop on onAppPause, re-search on resume (TT in the process-wide owner); screen on
  while the engine thinks and while the user's turn < N min.
- B7 PGN, last 50 games; the in-progress game = PGN + FEN checkpoint; headers carry level/takebacks/hints.
- B8 Perft on both cores (must agree); vendored Pirarucu vs upstream 3.4.0 on the Mac by fixed-depth
  node counts (deterministic) + a short match; WAC/STS subsets; LP3 nps; cutechess gauntlet assigns the
  Elo labels.

## Branch A: v1 puzzles (expert rulings, 2026-09-27)
Facts: counted in the 2026-09-09 dump: Popularity >= 90 and NbPlays >= 1000 leaves 1,692,441, plus
RD <= 80 leaves 1,323,923; ~150k per band at 1100-1600, 2,042 in the 2800s; most solutions 4 or 6
plies. Lichess (lila moveTest.ts) accepts any checkmating move at any step and king-onto-own-rook
castling. A wrong move reverts, switches to try mode and scores a fail. A hint marks the piece only
and makes the attempt unrated. Glicko: 1500, RD 500, min 45, vol 0.09, provisional while RD > 75.
- A1 ~50,000 puzzles: 2,000 per 100-point band from 400 to 2800 (300s folded in; everything at
  2800+). Filter Popularity >= 95, NbPlays >= 1000, RD <= 80, one per GameUrl, theme-mixed sampling.
  SUPERSEDES R1.4's 100-200K count.
- A2 One .txt per band, lines `id;FEN;UCI moves;rating;RD;themes`, sorted by rating; ~6.5 MB in about
  25 files; pinned to the 2026-09-09 dump plus its SHA-256. SUPERSEDES R1.4's binary layout.
- A3 Wrong move: take it back, the attempt stays failed and is scored once, try mode continues,
  Solution shows at once.
- A4 Any mating move at any step wins; accept king-onto-rook castling; promotion must match the
  stored piece unless the move mates.
- A5 500 ms hold, 250 ms setup animation, reply 300 ms after the user's move lands; the side to move
  after the setup move is at the bottom; last-move marker always; "White/Black to move" in the strip.
- A6 One hint level: a ring on the piece to move; the attempt becomes unrated; a hint after a mistake
  is free.
- A7 Glicko-2 1500 / RD 500 / vol 0.09 / RD floor 45 (SUPERSEDES R1.5's RD 350); one puzzle = one
  rating period; puzzle ratings fixed; "1500?" while RD > 75; window ±100 widening by 100; never
  replay a finished puzzle.
- A8 One JSON file (rating, RD, vol, pack SHA, finished Lichess ids, in-progress puzzle id + state
  unscored/failed/hinted), written via temp + rename on every result and in onAppPause; resume the
  same puzzle.
- A9 About: Lichess credit + per-puzzle lichess.org/training/<id> as text; the piece-set author and
  licence.
- A10 Open straight into the current puzzle; visible Menu (rating/history, About); themes v1.x,
  "Play the computer" v2.

## Reconciliation
- Puzzle pool: A1/A2 over R1.4 (counted data; text reuses the core parsers).
- Rating: A7 over R1.5.
- Hint: puzzle hint (A6, a ring on the piece, unrated) and game hint (B5, best move, counted) are two
  different things. Name them Puzzle Hint vs Game Hint.
- Already settled, so not re-asked: promotion picker (R1.6), coordinates inside squares (R1.7),
  hardware keys (R1.9: wheel only), shared rules core for v3 (R1.1).

## Pirarucu spike (facts, 2026-09-27; scratch in now spikes/pirarucu)
- VIABLE. Commit 987dd02 (v3.4.0, 2020). pirarucu-common builds unchanged on Kotlin 2.3.20 (no flags).
  The Light plugin's own scan logic, copied verbatim: 0 violations (original and flattened; the jvm
  module has 3, so the scanner works).
- Perft exact: startpos d5 4,865,609; Kiwipete d4 4,085,603. Mac M3 Pro, 1 thread: ~1.85M nps
  (depth 22-23 in 10 s). LP3 unmeasured.
- Changes: replace PlatformSpecific with a plain object (stdlib); delete UCI/epd/listener files;
  @Volatile SearchOptions.stop (verified stop from another thread at 1500 ms). A node cap is about 5
  lines. Pirarucu has NO multi-PV; for B2's top-N pick, score root moves with shallow searches or add
  evaluation noise.
- API: MainSearch(opts, listener, TT(mb), PawnEvaluationCache(mb), History()); startControl();
  search(board); searchInfo.bestMove/bestScore. BoardFactory.getBoard(fen) + doMove(Move.getMove(..))
  (which does NOT check legality: our core validates). No hidden global state that affects search.
- Risks: default TT = 256 MB and pawn cache = 32 MB, so ALWAYS pass explicit sizes (8-16 / 1-4 MB). A
  stop before depth 1 gives Move.NONE, so we need a fallback move + a minimum search time.
  bestMove is read from the TT, so record the root best move directly. 1024-ply limit. We own the
  fork. Not yet built inside the Light plugin or run on ART.

## Round 2B (expert rulings, 2026-09-27)
- E1 GPLv3-or-later with Pirarucu; Karballo (MIT) is the fallback. Stay relicensable: the maintainer keeps
  sole copyright of everything outside vendor/ (no outside contributions without a copyright
  assignment). v1 code has no GPL dependency (cburnett BSD-3, Lichess CC0). NEEDS THE MAINTAINER'S OK.
- E2 Level-8 "Think time" 3 s (default) / 10 s / 30 s = a wall-time cap on the node budget. "Move now"
  at every level plays the best move of the last COMPLETED iteration. If LP3 nps is far below
  0.3-1 M, the default becomes 10 s. Size the TT to the heap before offering 30 s.
- E3 Top-N sampling built in our wrapper: re-search the root excluding the best-so-far, N times
  (cheap at low depth; N=1 at level 8). Eval noise rejected (it breaks node-count parity with
  upstream). Mac feel check (3 games/level) before cutechess. On Karballo: built-in limiter for L1-5.
- E4 No engine in v1 puzzles.
- E5 Thread-priority calls pass the plugin (LightSdkPlugin.kt:78-127), but don't raise priority: keep
  nice 0. In the benchmark, log the engine thread's core (/proc/self/task/<tid>/stat field 39).
- E6 No seat recovery in v3.
- E7 Cap of 5 games; "Your move" first, sorted by time left; a batched sync endpoint (<= 5 {gameId, since}).
- E8 Rematch through the old log: rematchOffer{newGameId, joinToken}; after a result the relay
  accepts only rematchOffer/Accept/Decline, one offer per game, token expires in 48 h.
- E9 Pin v=major.minor per game; minor versions are additive; a major mismatch shows "Update Chess to
  continue this game"; the relay keeps serving every major pinned by a live game; /health lists them.
- E10 No Lichess ADR now.
- E11 v2 PRs: (1) vendor + plugin scan + light-build rehearsal + node-count parity; (2) engine holder
  + LP3 nps/time-to-depth + core logging = GO/NO-GO gate (else Karballo); (3) levels + top-N + feel
  check + gauntlet; (4) game screen (pause/stop, screen-on, Move now); (5) PGN history + resume;
  (6) book.

## Round 2A (expert rulings, 2026-09-27)
- D1 The strip shows "Solved +12" / "Failed −9" / "Solved, unrated" plus Next; never auto-advance;
  the next puzzle preloads once the result is scored; after a fail, Next appears once the Solution
  has played.
- D2 Missed = the last 100 Attempts that were Failed OR Hinted, newest first; replays are unrated;
  an entry leaves after a clean replay.
- D3 Keep the screen on while an Attempt is Open and the last touch/wheel event was < 5 min ago.
- D4 First-launch question: 4 plain-language rows (800/1200/1600/2000, RD 500) + Skip (1500); never
  re-asked, except through "Reset rating" in the rating screen.
- D5 The pool grows only through Tool updates; v1.x declares NO INTERNET permission.
- D6 One Tool "Chess"; the Menu grows by version; one saved-state file per mode; open on the mode
  LAST USED; the v3 menu entry shows "Your move: N".
- D7 About: GPLv3-or-later + source URL as text; Lichess CC0 + dump date; cburnett; Apache-2.0
  notices for bundled libraries; later Pirarucu (ratosh, GPLv3) and the book's provenance.
- D8 Glossary (Reader format, grouped Platform/Board/Puzzles/Games/Network): Puzzle Rating = the
  puzzle's difficulty; Player Rating = the user's; Attempt states Open/Failed/Hinted/Solved; add
  Ply, Try Mode, Review, Missed, Result, Tool; Live is a state of a Correspondence Game.
- D9 v1 PRs: (1) template + lighttool.toml identity + ci.sh + light-build rehearsal + rules core
  with perft; (2) pack script + COMMITTED pack .txt (~6.5 MB; the builder is offline) + loader
  tests; (3) board + input; (4) puzzle flow + Glicko + save file with schemaVersion, lenient read,
  .bak; (5) release docs (README, LICENSE/NOTICE, SECURITY, CONTRIBUTING, RELEASING, one-line
  privacy statement, three-line release notes, R8 run, upgrade-path test, public-content check).
  Light needs: open source, a public commit, a monotonically increasing versionCode, a unique id,
  allowlisted permissions, an ethos review; the portal isn't live (#204); screenshots/privacy
  requirements UNKNOWN. Gate: Light's GPL answer.
- D10 The palette is all gray, so a screencap equals what the filter shows (belief; confirm with one
  photo). Contrast targets are unit-tested pixel constants: squares >= 60 levels apart; the
  last-move shade >= 25 from both squares + an outline; markers readable on both. LP3 photo as the
  final check. No board flip in v1 (v2 adds it). No sound in v1.

## Round 3 (expert rulings, 2026-09-27)
- F1 Pack update: finished ids (kept even if the puzzle left), Player Rating, Missed (minus puzzles
  that left) carry over; an Open Attempt whose puzzle left is dropped; if still present, it restarts
  from the setup on the NEW line and its Failed/Hinted state stays.
- F2 Wheel click: returns to the latest Position while in Review, in every mode; otherwise false (no
  click-for-Next).
- F3 Wheel on scrollable screens: one row / ~3 lines per detent, consumes every event even at the
  ends; false only where nothing scrolls.
- F4 Clean Missed replays: silent.
- F5 Reset rating: only the Player Rating (re-asks D4); keeps finished + Missed; "Tap again to reset".
- F6 The seed screen before the first puzzle; on the first puzzle the strip reads "Tap a piece, then a
  square".
- F7 v2.x "Play from here": only from the puzzle's start position, the solver's side, current Level;
  hidden if the position is already decided; SetUp/FEN PGN headers; replacing a game in progress
  needs "Ends your current game" (second tap), and the old one is saved as unfinished.
- F8 The engine never offers a draw or resigns.
- F9 An outgoing rematch offer and an outstanding invite count toward the cap of 5; incoming don't;
  refuse "Finish a game first".
- F10 Device/build facts to measure: generated manifest allowBackup (a build fact; keep seat secrets
  out of backup paths); Runtime.maxMemory; screencap vs photo gray levels; nps + time-to-depth +
  core; cold start to the first puzzle < 500 ms; 10-min thermal soak at 30 s think; (v3) LightWork
  runs in Doze.
- F11 v3 moves need confirmation (pending piece + Send/Undo); v1/v2 never. v3 local opponent label
  (default: the first 4 chars of the invite), never sent. Per-IP rate limit on invite redemption
  (check the Workers rate-limit binding). v2/v3 "Moves" screen (SAN, two columns, wheel). Engine
  move animation 200 ms; user moves instant. Strip styled per light-reader DESIGN.md. Semantics
  labels on buttons/rows; board semantics deferred. English only, copy in one strings object.
  cburnett only; no v1 settings; v2 settings = Level, Think time, Colour. v1.x themes: Mate in 1 /
  Mate in 2 / Mate in 3+ / Endgame / Any. NEVER streaks, daily puzzle, badges. Rating history = text
  list of the last 50. Deferred: sound, premoves, eval bar, analysis, PGN export, Chess960,
  pass-and-play. Takeback while thinking = stop, then undo 2 plies. Icon: gray cburnett knight. No
  telemetry.
Contradictions resolved:
 1 No theme filter in v1 (R1.5's filter moves to v1.x).
 2 The strip shows <= 3 buttons chosen by context: your turn = Takeback, Game Hint, Menu; engine
   thinking = Move now, Menu; Resign and Draw offer live in the Menu.
 3 While the engine thinks the board is locked; the strip stays live.
 4 One screen-on rule everywhere: 5 min since the last touch/wheel event (supersedes B6's rule).
 5 The save file uses "Open"; the schema includes Missed + rating history, both capped.
 6 v2 Takeback truncates the event list and bumps a counter in the PGN headers; v3 has NO takeback.
 7 v1 doesn't wait for Light's GPL answer; if GPL is refused, v1 ships permissive and v2 uses Karballo.
 8 Canonical FEN for the digest: the ep square only when a legal ep capture exists; castling in KQkq
   order; unit-tested.
 9 "claim" = timeout claim only; threefold and 50-move are automatic in every mode.

## Round 4 (expert rulings, 2026-09-27): FRONTIER EMPTY
- G1 The engine accepts a draw if (a) eval <= -50 cp past move 30, or (b) |eval| <= 15 cp on its last
  3 searches, move >= 40, neither side has more than R+minor of non-pawn material (no queens); judged
  on the true eval even at weakened Levels; answered at once ("Draw declined"/"Draw agreed"); a
  re-offer is allowed only after 10 more moves.
- G2 v3 outstanding invite row "Waiting for a friend · ABCD-EFGH · expires in N h"; cancel = second
  tap + DELETE on the relay; the slot is freed only once the relay confirms; if already redeemed, the
  row becomes the game; expired invites drop at 48 h.
- G3 "Live · Your move" only once both WSS connections are confirmed (the relay reports presence);
  it drops after the opponent has been gone >= 10 s.
- Defaults: v3 own colour at the bottom, no flip. v3 draw offers FIDE-style: offered with your own
  move; the opponent's next move implicitly declines; one open offer; the strip shows "Draw offered
  · Accept / Decline".
Waiting on outside answers (not design): Light on GPL; production push/alert/badge; privacy
statement; screenshot/submission requirements; the F10 device measurements (Pirarucu GO/NO-GO,
default Think time).

## Owner rulings (2026-09-27)
- LICENCE: MIT for now (SUPERSEDES R1.3 and E1). v1 has no GPL code (own core, Lichess CC0,
  cburnett BSD-3). No copyright-assignment rule is needed. If v2 vendors Pirarucu, the Tool as
  distributed becomes GPLv3 and our files stay MIT inside it. The choice is made at the start of v2.
- ENGINE: spike Karballo as well as Pirarucu, for reasons beyond the licence (built-in strength
  limiter, API, speed). v2 PR 1 picks the engine from both spikes' measurements and, if it is
  Pirarucu, Light's answer on a GPLv3 Tool. Karballo is no longer just the fallback.
- Light questions (production push, an alert/badge method, a privacy statement for a Tool that
  talks to its own server, listing requirements): unknown, and none of them blocks development.
- LICENCE, corrected by the owner the same day (SUPERSEDES the "MIT for now" entry above): GPL-3.0-
  or-later now, relicensable later (for example to MIT). This is E1's original ruling. It requires the
  maintainer to hold the copyright of everything outside vendor/, so no outside code without a
  copyright assignment (CONTRIBUTING.md). A relicense applies only to future releases, and a build
  that vendors Pirarucu stays GPLv3 as a whole.

## Karballo spike (facts, 2026-09-27; spikes/karballo)
- Viable with work: no expect/actual; 0 plugin-scan violations (the scanner misses a bare
  `javaClass`, a light-sdk gap to report, which must come from the maintainer personally); 15
  lowercase fixes; perft exact.
- Much weaker than Pirarucu: 1.5/20 at 250 ms (~400 Elo); 7-20x slower to reach depth 12; ~1.6M nps
  vs 1.85M.
- Its limiter randomly rejects improvements at every node; it is not human-like and costs full CPU at
  every Elo. The bundled Fruit book has an unclear licence, so it is not used.
- Result: Pirarucu stays the v2 engine (ADR 0001 stays proposed until the LP3 benchmark); Karballo
  remains the fallback for a fully MIT Tool.

## Pack fill (expert ruling, 2026-09-28)
- Measured: A1's strict filter yields 33,707 puzzles (Band 400 and 2700+ empty; only 800-2200 reach
  2,000). RULING: keep a tiered fill (A1 strict first, then R1.4's looser filter only where a Band is
  short); no uniform filter, no third tier. Band 400 = 608 and top Band 2800 = 363 are accepted (the
  ±100 window draws from the neighbouring Band).
- In short Bands: all strict puzzles, then loose ones ordered by Popularity, then NbPlays, still
  theme-mixed; one puzzle per game across both tiers.
- The Glicko-2 update uses each puzzle's own RD (from the Pack line) rather than treating its rating
  as exact; the Puzzle Rating stays fixed (A7). Applies in PR 4.
- The build prints and pins a per-Band report: count per tier and median RD.
- Revisit only if Band 400's size is questioned: count Band 400/2800 with one strict condition
  relaxed at a time (Popularity >= 90, NbPlays >= 500, RD <= 120).

## v1 PR 4 rulings (orchestrator, 2026-09-28)
- Cold start: the SDK's LightActivity keeps its splash screen up for at least 1 s after onCreate
  (setKeepOnScreenCondition < 1000), so F10b's "< 500 ms to the first puzzle" can't be met by the Tool.
  RESTATED: the first Puzzle is drawn within 500 ms of the splash ending, i.e. <= 1.5 s from process
  start on the LP3. The 1 s floor is Light's; whether to raise it is the maintainer's call (Light's AI
  policy).
- Player Rating settling: with RD 500, vol 0.09, tau 0.75 and one Puzzle per rating period, RD settles
  at about 73-74, so the "?" goes after about 48 Puzzles and the 45 floor is never reached. ACCEPTED as
  is: RD <= 75 is Lichess's own "established" threshold. No change.

## v2 PR 1: Pirarucu vendored (facts and implementation choices, 2026-09-28)
- Layout: `tool/src/main/kotlin/vendor/pirarucu/<package>/`, package names kept (`pirarucu.*`), so
  unchanged files are byte-for-byte upstream. It must live under src/main/kotlin (the plugin bans
  srcDir); LICENSE and provenance go in `vendor/pirarucu/` at the root (the extractor takes only .kt
  there). `scripts/vendor-pirarucu.py` regenerates it; `--check` and `--parity` guard it.
- Plugin scan: the real `:tool:assembleDebug` scan covers the vendored tree (a probe `.javaClass` in
  it fails the build); 0 violations.
- Parity: upstream 987dd02 (pirarucu-common + its JVM `actual`, compiled at Kotlin API 2.0 because of
  one `toUpperCase()` in applyConfig) and the vendored copy agree node for node, move and score on 25
  fixed-depth searches (5 positions, depths 8, 9, 10, 12, 14); pinned in
  `tool/src/test/resources/engine/parity.txt`. Fresh 16 MB TT / 2 MB pawn cache per search.
- "Completed iteration" = an iteration whose result is inside the aspiration window, or fails high
  (the move is proven better than the window); a fail low never replaces the move. The root records
  its best move when not stopped; the TT-read bestMove agrees on all 25 parity searches.
- Engine board = the Position at the last 50-move reset plus the Moves after it: repetitions are seen,
  the 1,024-entry game history never fills.
- Wall limits are exact through a timer in EngineHost (Pirarucu reads the clock every 65,536 nodes);
  a per-search StopHandle keeps a late stop or timer from reaching the next search.
- Hash: 16 MB TT, 2 MB pawn cache (ADR 0001 range). Node budget overshoot: at most a few quiescence
  nodes (1,004 for 1,000; 20,001 for 20,000).
- Benchmark trigger: a debug-only `@EntryPoint` (src/debug, never in release or Light's builder) runs
  the suite when `files/chess-bench` exists. The SDK allows one @EntryPoint, so if main ever needs one,
  the trigger moves into it. Numbers come from a debuggable build.
- Release APK unchanged (27,855,909 bytes): R8 drops the engine until a screen calls it. Debug APK
  +246 KB.
- Mac JVM (M3 Pro, JDK 17, 1 thread, 5 runs): ~1.9M nps P50 (1.7-3.0M by position); depth 14 in
  195 ms P50 / 331 ms P90; 3 s reaches depth 20 P50 (18-28). The LP3 run is v2 PR 2's gate.

## v2 PR 2: LP3 benchmark (facts and implementation choices, 2026-09-28)
- Harness fixes: `logcat -s ChessBench:I` (the old second spec `ChessBench:E` replaced the first and
  hid every Info line, so bench.sh never saw "done"); bench.sh exits 0 on "done", reads and validates
  `stay_on_while_plugged_in` before any change and restores exactly that value (delete if unset) on
  exit, Ctrl-C, TERM and HUP. `scripts/bench-test.sh` checks this against a fake adb (CI + ci.sh).
- Thread: the search always ran on `chess-engine` through `EngineHost.search`; logcat's tid == pid was
  only the main thread logging the lines. Each search line now carries `thread=` and `tid=` sampled on
  the searching thread; `BenchSuiteJvmTest` asserts it is the engine thread, not the caller's.
- Benchmark build type `benchmark`: not debuggable (aapt: no `application-debuggable`), dev-signed, not
  minified, `matchingFallbacks = release`. It alone owns `src/benchmark` and `src/testBenchmark`: the
  plugin bans srcDir, so a source set shared with debug is impossible; `-Pbench.debuggable=true` builds
  a debuggable twin of the same APK instead. Debug and release dex carry 0 bench references; release is
  still 27,855,909 bytes; the extractor takes 88 files and none from src/benchmark; the unsigned
  offline release build passes.
- Trigger without run-as: a file adb writes under `/sdcard/Android/data/<pkg>/files` is created
  `shell:ext_data_rw`, which the Tool cannot read (seen on the emulator), and the entry point has no
  Context or Intent. So bench.sh builds with `-Pbench.id=<fresh> -Pbench.runs=<n>` (BuildConfig,
  `buildConfig = true`) and the Tool runs the suite once per id, writing the id to its own
  `files/chess-bench-ran` first (no crash loop, no rerun of the same build).
- The LP3, 10 runs + warm-up, non-debuggable benchmark build: think-time nps P50 558,116 /
  P90 862,055 (all 120 searches: 567,309 / 831,009); time to depth 14 P50 693 ms / P90 1,151 ms; depth
  in 3 s P50 18 / P90 22 (16-22 by position: ruylopez 16, endgame 22). Engine thread `chess-engine`
  tid 12064 (pid and main thread 12029) on the A78 cores only (6: 111 samples, 7: 9). maxMemory 128 MB,
  32 MB used at the end. Thermal status 0 before and 0 immediately after.
- Same session, debuggable twin: nps P50 153,614 / P90 263,687; depth 14 P50 2,551 / P90 4,301 ms;
  depth in 3 s P50 14 / P90 19; cores 6-7; thermal 0/0. It matches the earlier debug-build run (158,566
  nps, 2,473 ms). Non-debuggable / debuggable: 3.6x nps at P50 (3.3x P90), 3.7x faster to depth 14.
- Against E2: 0.56M nps is inside the 0.3-1M band, so the 3 s default stands; Pirarucu passes the
  E11 speed gate (GO pending the owner's call). The Tool must be measured as Light's release builder
  ships it (minified, not debuggable); the benchmark build is the closest proxy without R8.

## Takeback rule corrected (orchestrator, 2026-09-28)
- F11's "Takeback while thinking = stop, then undo 2 plies" is wrong: during the computer's think
  the user's Move is the last ply, so undoing 2 would also remove the computer's previous reply, which
  it would then replay. RULING (as built on feat/v2-record): a Takeback cuts the event list back to
  just before the user's latest Move (1 ply while the computer thinks, 2 after it has replied), and
  stops any search first. No Takeback before the user's first Move or after the Game is over.

## v2 PR 5 core: game record and resume (implementation choices, 2026-09-28)
- Code: `tool/src/main/kotlin/com/yarosz/chess/games/`, pure Kotlin, no UI (the game screen comes
  later). `GameRecord` = a Game plus what is recorded with it (user's Side, Level, Level-8 think
  time, Takebacks, Game Hints, date); `Pgn` writes and reads it; `GameData`/`GameStore` save it.
- Tags (B5/B7, F7): the Seven Tag Roster (Event "Game against the computer", Site "?", Round "-",
  White/Black "You"/"Computer"), then SetUp "1" + FEN when the start isn't the standard Position,
  then Termination, then ours, named like PGN's own: `Level` (1-8), `ThinkTime` (whole seconds, when
  set), `Takebacks`, `GameHints`. Termination is a sentence per Result: "White wins by checkmate",
  "Black wins by resignation", "Draw by agreement", "Draw by threefold repetition", "Draw by the
  50-move rule", "Draw by insufficient material", "Draw by stalemate"; none while the Game goes on.
- Game Events that aren't Moves are PGN-style comment commands in place: `{[%draw offer white]}`,
  `{[%draw accept black]}`, `{[%draw refuse black]}`. A resignation has none: a decisive Result the
  Moves don't reach by checkmate reads back as the loser's resignation (so ordinary PGN resignations
  read too). A draw the Moves don't show without an accept command is rejected.
- Reader: rebuilds through the core; rejects illegal or unknown SAN, a Result tag the Game doesn't
  reach, a movetext marker that disagrees with the tag, bad Level/counter values, a bad FEN. Skips
  comments (other than the commands, and those only outside variations), NAGs, `!?` marks, nested
  variations, `;` comments and `%` lines; ignores unknown tags; a bad Date becomes "????.??.??".
  Movetext lines wrap at 79 columns; tag lines don't wrap.
- One file, `games.json` (schemaVersion 1), not a PGN file per game or one `.pgn`: `{current: {pgn,
  fen}, finished: [{pgn}]}`, newest first, capped at 50. Ending a Game (current to finished) and F7's
  replacement (old Game to finished as "*", new one current) are then one atomic save, schemaVersion
  and unknown fields have a place (the PuzzleData rule: later schemas only add), and the save
  pattern is the one `.bak` of `puzzles.json`. Size: about 1-2 KB per Game, under 100 KB at the cap.
  The temp + rename + `.bak` + `.corrupt` code moved from PuzzleStore into a shared `SaveFile`, which
  both stores now use (PuzzleStore's tests unchanged and green).
- Resume: the in-progress PGN replayed exactly (Position, events, open draw offer, counters). The FEN
  checkpoint is the fallback when the PGN doesn't read (a file from a newer build): the Game restarts
  from that Position without its earlier Moves or counters. When both read, the PGN wins.
- Takeback (contradiction 6): cut the event list just before the user's latest Move, so it drops that
  Move, the computer's reply if there is one yet (F11's "takeback while thinking" is then one ply),
  and any draw event after it; the counter goes up by one; the user is to move. No Takeback once the
  Game is over (it goes to the history) or before the user's first Move.
- Tests: 200 seeded random Games (the rules tests' generator, now `RandomGames`, a quarter from set-up
  Positions, ended by the rules, resigned, agreed or left unfinished, random counters) round-trip
  equal and re-write to the same text; hand-written PGN (the Opera Game with comments, NAGs, nested
  variations, `;` and `%` lines); every Result and Termination; SetUp/FEN starts; castling, promotion
  and disambiguation SAN; the store (round trip, `.bak`, unknown fields, the 50 cap's order, F7,
  Takeback counter, the checkpoint fallback).

## Opening book rulings (expert, 2026-09-28; supersedes B4's source detail)
Facts: database.lichess.org's standard rated monthly dumps are CC0 1.0 (2018-01: 5.47 GB, 17.9M
games; 2026-08: 30.1 GB). No official elite subset. Broadcasts are CC BY-SA; masters data is API-only;
nikonoel has no licence; TWIC is personal use only: all rejected.
- (1) Source: stream `lichess_db_standard_rated_2018-01.pgn.zst` (URL + SHA-256 pinned), no local copy.
  Filter: both players >= 2200; base + 40 x increment >= 180 s; Termination "Normal"; the first 20
  plies. Keep a (Position, Move) pair with >= 10 occurrences AND >= 5% of the Position's games; drop a
  Move with >= 30 games where the side playing it scores < 40%. Weight = game count scaled per
  Position to 16 bits; learn = 0. Credit Lichess in About.
- (2) At most 40,000 entries (<= 640 KB) in one book.bin; over the cap, raise the minimum count until
  it fits. Commit the entry count and the book.bin SHA-256.
- (3) Build: a Kotlin JVM CLI on our rules core (SAN, Polyglot keys from the spec's Random64 table,
  sorted big-endian 16-byte entries), as a mise task; no Python. Gate: our keys against the spec's
  published keys. The build and the game share the reader.
- (4) Levels: L1 no book; L2-4 to ply 8, pick proportional to sqrt(weight); L5-8 to ply 20, pick
  proportional to weight; out of book, or once the user leaves book, the engine for the rest of the
  Game. The book RNG seed is stored with the saved Game (resume and Takeback replay the same pick);
  the strip says nothing about book Moves; the Game Hint stays the engine's best Move (B5); lookup is
  by Polyglot key (transpositions covered), and the ply limit counts real Game plies.

## v2 PR 6: the Book (facts and implementation choices, 2026-09-28)
- Spec: https://hgm.nubati.net/book_format.html. `scripts/polyglot-random64.sh` generates
  `rules/PolyglotRandom64.kt` from the page (781 constants, none typed by hand; `--check`).
  `Position.polyglotKey` matches all nine published test keys, from the spec's FENs and by playing
  its moves. The en passant file counts whenever a side-to-move pawn stands beside the pushed pawn,
  legal capture or not (the spec says so explicitly), unlike our canonical FEN.
- Dump pin: SHA-256 `8ac6ff9d722a4bba1c1d72c700523408dff2e09cc52cbfe4e454289ca60e8d6b`, from
  Lichess's `standard/sha256sums.txt`; the build hashes the streamed bytes through a fifo and
  publishes only on a match.
- Result: 17,945,784 games read, 77,509 kept (0.43%, below the ruling's 0.60% sample estimate of
  about 107k; the Termination filter is the likely difference, not measured), 0 unparseable; 7,082
  entries in 4,801 Positions, 113,312 bytes, SHA-256
  `0e5b8eb75b6556cf66d8a9526c682abdc32bc340d7565c7137be7b404d8c311c`. The cap is far away, so the
  minimum count stays 10. Two streaming runs and a rebuild from the kept games
  give identical bytes.
- Choices beyond the rulings: games with a SetUp/FEN header are skipped; entries in Positions the
  Book can't reach from the start by book Moves within 20 plies are pruned (they could never be
  played, since leaving the Book is final), before the cap applies; weights are scaled so each
  Position's top Move weighs 65,535 (rounded, at least 1); ties sort by weight descending, then move
  code. The reader rejects a king's two-square step (Polyglot castles only as king takes rook) and
  checks every Move with the core.
- `BookPolicy.pick(book, start, moves, seed)` is pure: it replays the Game's Moves, returns null once
  any Move wasn't a book Move (either side) or past the Level's ply, and draws with
  `Random(seed * mix + ply)`, so a pick depends only on the seed and the ply. The game screen (PR 4)
  wires it in and stores the seed.

## v2 PR 3: Levels (tuned values and calibration method, 2026-09-28; docs/levels.md)
- Values (nodes per search / depth cap / N / margin cp): L1 60/2/4/100, L2 400/4/4/150, L3 1,000/-/3/80,
  L4 2,500/-/3/55, L5 6,000/-/2/35, L6 25,000/-/2/20, L7 120,000/-/2/15; L8 one plain search with
  Think time × 1,000 nodes/ms (3M/10M/30M), wall-capped at 3/10/30 s. Estimated LP3 time per Move
  (nodes / 558K nps): L1-L5 < 25 ms, L6 ~90 ms, L7 ~430 ms; L8 is clock-bound on the LP3.
- Method: Mac JVM only. Nothing installed (no Stockfish, cutechess-cli or python-chess), so a Kotlin
  match runner in the Tool's test source set (`LevelCalibrationTest`, `-Dcalibrate=...`), our rules
  core as referee, fresh engines per Game, seeded and node-based, so every run replays. A self-play
  ladder (80 Games per pair), a gauntlet against Karballo's limiter (the spike's flattened Karballo
  behind a line server, `spikes/karballo/flat/serve`, in its own process), a blunder profile (40
  middlegames × 5 seeds, judged at depth 12), and Level 1 against a random mover.
- Measured: ladder gaps +436 to +636 (span ~3,450); gauntlet gaps +164 to +394 (span ~2,020);
  cp loss 131/61/59/32/26/17/13/11, blunders per 40 7.4/3.2/2.2/0.6/0.6/0/0/0; Level 1 38.5/40 against
  random; Level 8 (3 s of LP3 nodes) 75/80 against Level 7 and 31.5/40 against full Karballo at 2M
  nodes.
- The 150-300 target cannot hold in self-play with a beginner at Level 1 and full strength at Level 8:
  self-play inflates gaps. The settings aim for even gaps. The gauntlet gaps (closer to human feel) are
  within or near the target.
- NO Elo labels (B2): Karballo's own labels measure 505 Elo apart from 500 to 1000 but 755 from 1000 to
  1500, so an "approx." label would be off by ~250 depending on the reference. Levels show by number.
  Labels wait for a Stockfish UCI_Elo gauntlet (B8).
Implementation choices beyond the log:
- The random pick is seeded by (Game seed, Ply), so it does not depend on what was searched before; a
  pick is uniform among the candidates. Sampling stops at the first search outside the margin, and N
  never exceeds the number of legal Moves.
- Move now below Level 8: the search in progress stops and keeps its last completed iteration (a
  candidate if within the margin), and no further search starts. Before the first search completes
  depth 1, the fallback Move plays (as in v2 PR 1). EngineHost.play enforces Level 8's Think time as a
  Move now, to the millisecond.
- G1's true eval = the first, unrestricted search at the Level's own budget (`LevelMove.trueScore`). At
  Levels 1-2 it is shallow (depth 2-4). If PR 4 finds draw answers noisy there, add a separate eval
  search on a small engine of its own (not the shared TT, which would strengthen the next Move).
- The transposition table carries over between the searches of a choice and between Moves. A Game
  replays exactly from a fresh engine; a resumed or taken-back Game on a warm engine may choose
  differently (PR 5 should replay from a fresh engine if exact replay matters).
- Levels 6-7 keep N = 2 with a small margin (20/15 cp), so repeated Games don't repeat.
- The margin shrinks from Level 2 up; Level 1's margin (100) is below Level 2's (150). Level 1's
  blunders come from its 60-node, depth-2 search: margin 400 with N = 6 had the same blunder rate (7.7
  per 40) but played ~280 Elo weaker in self-play, which would widen the Level 1-2 gap for nothing.
- CONTEXT.md gains Think Time and Move Now.

## Stockfish for calibration (owner, 2026-09-28)
- The owner approved installing Stockfish on the Mac (for example `brew install stockfish`) to
  calibrate the Levels against UCI_LimitStrength / UCI_Elo, "maybe later". When it's done, rerun
  LevelCalibrationTest's anchor mode against Stockfish and, if the fit is consistent, show
  "approx." Elo labels (B2). Until then the UI shows Levels only (v2 PR 3).

## v2 PR 4: the game screen (rulings and implementation choices, 2026-09-28)
Rulings the log didn't make, each chosen as the one most consistent with it:
- R4.1 Result strip: "Next" and Menu, where Next ("Start a new game") opens the new-game page. The
  brief's "Next / New game" is read as one button: a Result next to three buttons (Next, New game,
  Menu) has 51 dp left, which fits no Result in two lines (StripFitTest).
- R4.2 The Game Hint's strip label is "Hint", its semantics label "Game Hint: show the computer's best
  Move" (F11). "Game Hint" next to Takeback and Menu leaves 0 dp for the status. The user's-Move status
  is "Your move", which wraps in the 67 dp those three buttons leave.
- R4.3 Takeback while the computer thinks (contradiction 2 keeps that strip to Move now and Menu) is in
  the Menu, where Takeback also sits on the user's Move. The "Takeback rule corrected" cut applies in
  both.
- R4.4 Game Hint = a plain Level 8 search at the default Think Time (3 s, 3M nodes), whatever the Game's
  Think Time (30 s would be too long to wait for a hint). While it runs the strip reads "Finding a Game
  Hint" with Menu, and the board stays live: a Move stops the search and nothing is counted. It shows
  (Puzzle Hint ring on the piece, target mark on its square) for 5 s or until the user's Move ("shown
  briefly", B5), and it is counted once shown.
- R4.5 Draw offers are made from the Menu, on the user's Move only (the true eval is the computer's own
  search, and while it thinks there is none for the Position). The answer shows in place:
  "Draw declined", or back to the board with the Result "Draw agreed". G1's terms: "past move 30" =
  the Position's move number > 30; "move >= 40" = move number >= 40; "10 more moves" = 10 of each
  side's, 20 Plies; "R + minor" = non-pawn material <= 8 (minor 3, rook 5), and no queen on the board.
  With no eval yet (only book Moves), the computer declines.
- R4.6 The true evals G1 needs are saved with the Game, as Lichess's `{[%eval -0.52]}` comment after
  each Move the computer searched (pawns, White's view), so a resume or a Takeback judges the next
  offer on the same evals. `GameRecord.evals`; a Takeback drops the evals of the Moves it cuts. A
  search stopped before depth 1 leaves no eval: on the emulator, Level 1 reported exactly 0.00 in
  lost Positions (a 60-node search can end before depth 1), which could feed G1's dead-level rule.
- R4.7 One Game seed (`Random.nextLong` at Start) drives both the Book pick and the Level pick, saved as
  the PGN tag `Seed`. "Random" picks the user's Side from the same seed.
- R4.8 Exact replay: at Levels 1-7 the engine forgets what earlier searches learned before each of the
  computer's Moves (`Engine.newGame`, cheap at these budgets), so a Move depends only on the Game, the
  Level and the seed, and a resume or a Takeback followed by the same Move gets the same reply.
  Level 8 keeps its table between Moves (it ends on the clock, so it can't replay exactly anyway) and
  clears it at each new Game. The calibration (v2 PR 3) carried the table between Moves at every
  Level; the effect of clearing it at Levels 1-7 was not measured, and is expected to be small next
  to the 164-394 Elo gauntlet gaps.
- R4.9 The computer's Move lands no sooner than 300 ms after the user's (A5's reply delay, so a book or
  Level 1 Move doesn't land with the user's), then slides in over 200 ms (F11).
- R4.10 The last mode lives in `mode.txt` (one word; missing = Puzzles), not in either mode's file: it
  is read before either mode loads (D6). The new-game choices (Level, Side, Think Time; first time
  Level 1, White, 3 s) and the board flip are new fields of `games.json`, schemaVersion still 1
  (fields only added).
- R4.11 On relaunch with no Game in progress, the game mode shows the last finished Game at its Result
  (Next leads on). "Play the computer" in the puzzle Menu returns to the Game in progress, else opens
  the new-game page. "Puzzles" in the game Menu stops the computer's search until the game mode
  shows again; the Menu itself doesn't stop it, and keeps the screen on while it thinks
  (contradiction 4).
- R4.12 The new-game page names the Side choice "Play as" (White, Black, Random): CONTEXT.md avoids
  "colour". Think Time is offered on it only at Level 8, and in the game Menu only for a Level 8 Game,
  where it applies from the computer's next Move; the record's ThinkTime tag keeps the latest value.
- R4.13 Result copy is from the user's view: "You won by checkmate", "You lost by checkmate", "You
  resigned", "Draw agreed", "Draw by stalemate", "Draw by repetition", "Draw by the 50-move rule",
  "Draw: insufficient material" ("Draw by insufficient material" needs three lines next to Next and
  Menu).
- R4.14 Games rows read "2026.09.28 · Level 3 · Won" (Won, Lost, Draw, Unfinished). A tap opens that
  Game at its Result, with the user's Side at the bottom, for Review by the wheel; Back returns.
- R4.15 "Play from here" (F7) is deferred to v2.x: it needs its own entry on the puzzle screen, a
  "decided Position" test (an eval before the Game starts) and the "Ends your current game" copy.
  LEDGER.md lists it. GameData.startNew already saves the replaced Game as unfinished.
- R4.16 Game strips hold one line on the LP3, but for a Result. Seen on the LP3 (Akkurat): "Your
  move" wrapped to "Your / move" next to Takeback, Hint and Menu, and "Computer thinking" to
  "Computer / thinking" next to Move now and Menu; the emulator's Roboto fit both, and StripFitTest
  allowed two lines (R4.2 had accepted the wrap). A state label broken word by word reads as a
  fault, where a Result is a sentence at rest, like the first Puzzle's status, and keeps R4.13's two
  lines. RULING, each change the one closest to an existing ruling:
  - The user's Move: "Your move", Hint, Menu. Takeback leaves the strip for the Menu, where R4.3 put
    it while the computer thinks and where it already sat on the user's Move.
  - The computer thinking: "Thinking", Move now, Menu (Move now and Menu stay, per contradiction 2).
  - Review, on the game screen and on the Games page: "Review · 12 of 40" and Latest alone. Next at
    the Result, Menu and Back come back at the latest Position, one tap (or a click, or a tap on the
    board: R1.9, F2) away. Next to Latest and Menu even "Review · 99 of 99" needs two lines, and a
    Game passes 100 Plies at move 50.
  - Unchanged: "Finding a Game Hint" with Menu, "New game" with Menu, a Result with Next and Menu
    (R4.1) or with Back.
  StripFitTest checks the game strips at one line (`GameStrip.statusLines`: 2 only for a Result) with
  "Review · 9999 of 9999". Its stand-in was recalibrated on the LP3 screencaps of v2 PR 4 (DESIGN.md
  "The strip"): it reproduces both wraps and both one-line strips, measures every string at least
  1 dp wider than the LP3 did, and every room narrower. The puzzle strips are unchanged.
- R4.17 A wheel key's up and repeats go where its down went. Seen on the LP3: in Review, a click
  returned to the latest Position and also turned the flashlight on (the torch was requested by
  com.lightos). LightActivity asks the screen about a key's down, repeats and up separately and
  forwards every LightDeviceKeys key the screen doesn't take to LightOS; our screens took only the
  down, so LightOS got the up. RULING: a screen that takes a press takes its repeats and its up; a
  press it leaves alone goes to LightOS whole (down, repeats, up), which keeps R1.9's "otherwise return
  false" (brightness and the flashlight when Chess has no use for the wheel). An up with no down seen
  isn't taken. A repeat gets its press's answer without acting again. `WheelViewModel` and
  `TakenKeys` (tested on the JVM) hold the rule for every screen. Also on fix/v1-keyup for v1.
Implementation:
- `games/GameFlow.kt` (pure): `GameState`, `GameFlow`, `DrawJudge` (G1), `ComputerReply` (a book Move
  or a `LevelRequest`, plus whether to start from a fresh engine). `GameOwner` runs it on
  `EngineHost.shared`; a stale reply (after a Takeback, Resign or new Game) is dropped by comparing the
  Game it was asked for. The file is written after every change (a force-stop skips onAppPause) and
  at once in onAppPause, which also stops the search; onScreenShow re-runs the computer's turn.
- `GameStrip` (pure) picks the strip; StripFitTest measures every state it returns, every Result for
  both Sides, and the Games Review strips.
