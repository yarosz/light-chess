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
  (cburnett's NOTICE credit SUPERSEDED by P1: NOTICE credits Chess's own CC0 pieces instead.)
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
  (Piece source SUPERSEDED by P1: an original CC0 set; the build-time conversion stays.)
  Tune shades on the LP3 (start: #D8D8D8 / #8C8C8C). Last move = shade + outline, check = ring,
  selection = heavy border, coordinates inside the edge squares. Exclude staunty and maestro (NC).
- R1.8 Layout: 312 dp board (39 dp = 117 px squares) plus a 48 dp strip: status + up to 3 text buttons.
  (The strip's width SUPERSEDED by N3: it spans the screen, with the back arrow at its left; its
  "Menu" text button SUPERSEDED by N4's mark, beside at most three text buttons. On the boards, the
  strip SUPERSEDED by layout E: a LightOS top bar over the board, E1, and an action row under it, E2.)
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
  "Play the computer" v2. (The "visible Menu" SUPERSEDED by N1 and N5: the rating, its history and
  About are Home's rows, and the Puzzle board has no Menu. "Open straight into the current puzzle"
  KEPT by N2, with Home underneath.)

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
  LAST USED; the v3 menu entry shows "Your move: N". (The root SUPERSEDED by N1-N2: Home is the
  root and the last-used place opens over it.)
- D7 About: GPLv3-or-later + source URL as text; Lichess CC0 + dump date; cburnett; Apache-2.0
  notices for bundled libraries; later Pirarucu (ratosh, GPLv3) and the book's provenance.
  (The cburnett credit SUPERSEDED by P1: one line crediting Chess's own CC0 pieces, reworded by P2.)
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
  ("cburnett only" and "Icon: gray cburnett knight" SUPERSEDED by P1: Chess's own CC0 set, and its
  knight for an icon; "cburnett only" then "one set" SUPERSEDED by P2: two sets, and the Piece Set is
  v1's one setting, so "no v1 settings" is SUPERSEDED by P2 for it alone.)
Contradictions resolved:
 1 No theme filter in v1 (R1.5's filter moves to v1.x).
 2 The strip shows <= 3 buttons chosen by context: your turn = Takeback, Game Hint, Menu; engine
   thinking = Move now, Menu; Resign and Draw offer live in the Menu. (AMENDED by N3 and N4: the
   back arrow at the left and the Menu mark at the right are not among the three text buttons.)
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
  (fields only added). (KEPT by N2, file and words; AMENDED by N1-N2: the last mode's place opens
  over Home, not as the root.)
- R4.11 On relaunch with no Game in progress, the game mode shows the last finished Game at its Result
  (Next leads on). "Play the computer" in the puzzle Menu returns to the Game in progress, else opens
  the new-game page. "Puzzles" in the game Menu stops the computer's search until the game mode
  shows again; the Menu itself doesn't stop it, and keeps the screen on while it thinks
  (contradiction 4). (The Menus' "Play the computer" and "Puzzles" SUPERSEDED by N1-N2: Home's
  rows; leaving the board for Home stops the search.)
- R4.12 The new-game page names the Side choice "Play as" (White, Black, Random): CONTEXT.md avoids
  "colour". Think Time is offered on it only at Level 8, and in the game Menu only for a Level 8 Game,
  where it applies from the computer's next Move; the record's ThinkTime tag keeps the latest value.
- R4.13 Result copy is from the user's view: "You won by checkmate", "You lost by checkmate", "You
  resigned", "Draw agreed", "Draw by stalemate", "Draw by repetition", "Draw by the 50-move rule",
  "Draw: insufficient material" ("Draw by insufficient material" needs three lines next to Next and
  Menu).
- R4.14 Games rows read "2026.09.28 · Level 3 · Won" (Won, Lost, Draw, Unfinished). A tap opens that
  Game at its Result, with the user's Side at the bottom, for Review by the wheel; Back returns.
  (The "Back" text button SUPERSEDED by N3: the strip's back arrow.)
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
    (R4.1) or with Back. (Back SUPERSEDED by N3's arrow, Menu by N4's mark.)
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

## Relay build decisions (2026-09-28)
Taken while building relay/ (docs/protocol.md v1.0), then checked against the code by review.
- H1 `seq` (the entry's place in the log, from 1) is the compare-and-swap key and the sync cursor
  (refines C4's "CAS on ply"). `ply` is still sent and checked: +1 for a `move`, unchanged for
  every other kind, because draw, resign, claim and rematch entries don't advance the Ply. Ply
  parity is checked for `move` and `claim` only.
- H2 The Relay stamps `side` from the seat secret; the phone never sends it.
- H3 The Relay checks timeout claims: only the side not to move may claim, and only once
  `now >= T + daysPerMove × 24 h`, where T is the latest `move`'s serverTime (the Game's
  `startedAt` before the first Move). A claim at the deadline itself is accepted. The Relay does
  not refuse a `move` sent after its own deadline; once it is stored, the claim is gone.
  (Stands; confirmed and refined by R3: which late kinds stay valid, and when the window closes.)
- H4 (superseded by R2: a `rematchOffer` now needs a closed log, and a `move` with `end: true`
  closes it.) The Relay can't see checkmate, stalemate or an automatic draw, so it accepts a
  `rematchOffer` on an open log, and the offer closes the log. The phone sends one only after its
  rules core has derived a Result.
- H5 Rematch join tokens are 256-bit (43 base64url characters) and not rate-limited; they expire
  48 h after the new Game is created (E8). Only the Seat that did not offer may answer, once
  (`rematchAccept` or `rematchDecline`). `/join` refuses any `joinToken` that isn't 43 base64url
  characters, so an Invite Code can't be redeemed there around the rate limit.
- H6 A Game created with an Invite Code lives in the Durable Object named `invite:<code>`, and its
  `gameId` is that object's id, so cancel and redeem of one code are serialised by one object.
  The object's operations don't await between reading and writing the Game, so exactly one of a
  cancel and a redeem wins. A code (and so its `gameId`) can be issued again only after its Game
  is deleted. A rematch Game gets a fresh unique id.
- H7 GET events returns `status` `waiting`, `active` or `closed`. Appended entries are pushed over
  the WebSocket to both Seats. One socket per Seat: a new socket closes the old one with 4001, and
  deleting the Game closes its sockets with 4004. A Game holds at most 2,000 entries (`log_full`,
  for every kind, terminal and rematch kinds included; superseded by R4: only `move`, `drawOffer`
  and `drawDecline` are capped). `daysPerMove` is 1, 3 or 7. Redemption by
  Invite Code is limited to 10 per 60 s per client IP (F11), counted before the body and the code
  are parsed.
- H8 Wrangler's `send_metrics` is false and Workers observability is off (C6). The Relay stores
  game ids, seat secret hashes, the invite's hash (kept after redemption only to tell a used token
  from a wrong one), times and the Game Events, including a rematch offer's join token. It stores
  no IP address and no name.

## Relay edge-case rulings (2026-09-28)
Expert rulings on the Relay's edge cases, with the facilitator's defaults for the follow-up
questions. Applied in relay/ and docs/protocol.md.
- R1 Draw offers. The Relay tracks `openDrawBy` (null, white or black) from kinds alone. A
  `drawOffer` comes only from the side not to move (it has just moved; G3 "offer with your move"),
  only at ply >= 1 and only while no offer is open; otherwise `403 not_your_turn` or
  `409 draw_already_offered`. `drawAccept` and `drawDecline` come only from the side to move, and
  only while the other side's offer is open; otherwise `409 no_draw_offer`. A `move` or a
  `drawDecline` clears the offer. GET events returns `openDrawBy`.
- R2 No `rematchOffer` on an open log (supersedes H4). A `move` carries an optional `"end": true`,
  set by the mover's rules core when that Move ends the Game (checkmate, stalemate, threefold, the
  50-move rule, a dead position). It closes the log and is part of the idempotent-retry
  comparison. A `rematchOffer` on an open log gets `409 game_not_over`.
- R3 Late Moves are accepted (H3 stands): a Game is lost on time only through a claim. The claim
  window opens at the deadline and closes when any `move` from the late side is stored; the
  opponent's clock then runs from that Move's serverTime. `resign`, `drawAccept`, `drawDecline` and
  a late `move` with `end` stay valid after the deadline.
- R4 The 2,000-entry cap applies only to `move`, `drawOffer` and `drawDecline` (supersedes H7's
  "for every kind"). `resign`, `drawAccept`, `claim` and the rematch kinds are exempt. The Relay has
  no N-ply draw rule.
- R5 Request bodies are capped at 4,096 bytes (`413 body_too_large`), checked on Content-Length and
  on the bytes read, so chunked bodies are capped too. WebSocket messages are capped at 256 bytes;
  a larger one gets `{type: "error", code: "bad_request"}`.
- R6 The version check on GET events, sync, join and live is enforced from the second major; the
  per-Game pin is stored now. The Kotlin client handles `400 unsupported_version` and
  `409 version_mismatch` on every endpoint.
- Default: a timeout claim is a one-tap "Claim win on time" button, never automatic (client).
- Default: an `end` flag that doesn't match the phone's own derived Result freezes the Game as
  "Out of sync" (C5), as for any other disagreement.
- Default: GET events returns `deadline`, the serverTime at which the side to move's time runs out
  (null while waiting or closed), so both phones show the same time left.
- Implementation reading: a closed log has no open offer, so `openDrawBy` is null once the log
  closes (by `drawAccept`, `resign`, `claim` or an ending `move`). A `drawOffer` at ply 0 gets
  `not_your_turn` (nobody has moved yet). `end` is either `true` or absent; `false` is a
  `bad_request`.

## v3 PR 1: the correspondence client core (rulings and implementation choices, 2026-09-28)
Code: `tool/src/main/kotlin/com/yarosz/chess/relay/` (protocol 1.0 types, `RelayClient`, the
`RelayTransport` seam, `OkHttpTransport`) and `correspondence/` (`GameLog`, `CorrespondenceGame`,
`CorrespondenceStore`, `Correspondence`, the sync engine). Pure Kotlin, no UI; tests on the JVM against
a fake Relay that mirrors `relay/src`.
- V1 The split. PR 1 compiles the client into the Tool and nothing calls it: no INTERNET in
  `lighttool.toml`, ToolMetadataTest, the privacy line ("Chess never uses the network. Nothing leaves
  this phone.") and ADR 0003 unchanged (D5 holds for v1/v2 as shipped). `NoNetworkYetTest` fails if
  Tool code outside the client constructs `OkHttpTransport`, `RelayClient` or `Correspondence`. PR 2
  brings together the permission, the new privacy copy and its ADR, the Relay's URL, the screens, the
  LightWork job and the live WebSocket.
- V2 HTTP library: OkHttp (5.3.2), used directly. It is already in the APK through the SDK's
  `ktor-client-okhttp`, so no dependency is added and the APK's contents and permissions don't change;
  C1 names OkHttp for the live WebSocket, so one library serves HTTPS and WSS; a blocking call on
  `Dispatchers.IO` fits a suspend LightWork job. Ktor would be a second layer over the same OkHttp.
  `RelayTransport` is the seam, so the choice is cheap to reverse. Plain HTTP only to 127.0.0.1 or
  localhost (the opt-in end-to-end test); the manifest allows no cleartext anyway.
- V3 Seat secrets (C3, F10): the generated manifest leaves `allowBackup` at Android's default (true),
  so anything in filesDir can be backed up. Every Correspondence Game, secrets included, lives in one
  file, `correspondence.json`, in the app's `no_backup` directory beside filesDir (what
  getNoBackupFilesDir returns; Auto Backup and device transfer never copy it), reached from filesDir
  because `android.content.Context` is blocked. One file keeps a pending entry and the Games it touches
  (a rematch and its offer) in one atomic `SaveFile` save. A restored phone has no Games (E6).
- V4 Timeout Claim in the rules core: `TimeoutClaim(side)` Game Event and `WinReason.TIME`. Only the
  side not to move may claim; the core has no clock, so `GameLog` checks the entry's serverTime against
  the Deadline (the latest Move's serverTime, or startedAt, plus days per Move). FIDE 6.9, exact case
  only: a claimant with a lone king draws (`DrawReason.INSUFFICIENT_MATERIAL`); no helpmate search for
  king-and-minor cases. PGN writes `{[%claim white]}` and "White wins on time"; UiCopy gains "You won on
  time" / "You lost on time" (unreachable against the computer; PR 2 owns the correspondence copy).
- V5 What the phone checks on every entry, its own included (`GameLog.check`): gameId, seq contiguity,
  the major of `v`, the digest's format, which fields a kind may carry, R1's draw parity, the side to
  move for a Move, the claim's Deadline, the rematch bookkeeping, then the rules core: the Move legal
  in its canonical UCI (castling only as the king's step; `e1h1` is refused on the wire), `ply`, the
  digest equal to Position.digest, and `end` equal to the core's Result. After each read it also
  compares the Relay's bookkeeping (latestSeq, latestPly, status, openDrawBy, deadline, rematch) with
  its own. Any refusal or disagreement stops the Game Out of Sync (C5); the refused entry never joins
  the log. An entry the phone already has must come back identical.
- V6 Versions (E9, R6): `unsupported_version` or `version_mismatch` from any endpoint, a Game view
  whose `v` has another major, or an entry of a kind 1.0 doesn't know, stops the Game as "Update Chess
  to continue this game" (not Out of Sync); its pending entry is kept for the updated Tool. Unknown
  fields are ignored; an unknown error code is treated like any other refusal.
- V7 Conflict recovery (C8): after `seq_conflict`, or a refusal that a fresh read explains, the phone
  checks the new entries and drafts the pending entry again at the new place if it still makes sense,
  else rolls it back (`rolledBack`, for the screen to say so). Exception: a pending Move displaced by
  the other Seat's draw offer is rolled back even though it is still legal, so the user sees the offer
  before their Move declines it. A refusal the fresh read doesn't explain (the log didn't move) rolls
  the entry back; `claim_too_early` rolls the claim back. A pending entry the read shows stored (a lost
  response) is simply confirmed.
- V8 One pending entry per Game (C8) means a draw offer is its own entry, sent once the Move is stored
  and before the reply, and nothing (not even a resignation) can be queued behind a Move waiting to
  send. PR 2 may present "Send and offer a draw" as the two steps.
- V9 Lost responses that protocol 1.0 can't recover: a lost redeem or join response loses the Seat
  (the secret is returned once); a lost create response leaves an invite nobody holds, which expires in
  48 h. Open for protocol 1.1: a seat secret chosen by the phone, or an idempotency key on redeem/join.
- V10 Background sync (`Correspondence.syncAll`, the LightWork job's body, wired in PR 2): sends queued
  cancels and pending entries, then reads, five per `/v1/sync`, every open invite, every Game being
  played, and a Game that is over while its rematch can still come (our offer unanswered, or 48 h after
  the Result for the other Seat's offer). Stopped Games aren't read. `SyncReport.retry` maps to
  `LightJobResult.Retry`; the periodic job stays scheduled only while `waitingOnOpponent` (C2);
  `yourMove` feeds "Your move: N". It never throws. One `Correspondence` per process (a Mutex serialises
  the screen and the job).
- V11 Rematch (E8, F9): the offerer takes the other Side, same days per Move. The new Game is created
  with a join token first (online), then the offer is appended to the old log (may wait to send). The
  new Game's invite is cancelled by itself when the offer is rolled back (the other Seat offered
  first), declined, or its old Game forgotten. Accepting joins first, then appends `rematchAccept`.
- V12 Invite cancel (G2): queued while offline; the row and its slot go only once the Relay confirms;
  `invite_redeemed` turns the row into the Game; `game_not_found` drops it.
- V13 The cap of five (E7, F9) counts open invites (a rematch's included) and Games being played; a
  stopped Game counts until the user forgets it (`forget`, allowed once a Game is over or stopped).
  Finished Games stay on the phone, the newest 50 by when they closed.
- V14 Time: the phone estimates the Relay's clock from the offset at its last response, only for the
  claim button and time left; the Relay's stamps decide (C2). A claim the estimate allows but the Relay
  refuses is rolled back as too early.
- V15 Tests: the fake Relay (`FakeRelay`, test sources) follows `relay/src` route by route; bad weather
  (`FlakyTransport`: offline, lost response, duplicate, 503 restart) and interleavings are injected
  per request. The property test's defaults are 40 seeds × 250 steps (`-Dcorrespondence.seeds=`).
  `RelayEndToEndTest` runs only with `-Drelay.e2e=<local Worker URL>` (relay/README.md).

## v3 PR 2 (expert rulings, 2026-09-28)
A fresh chess-expert agent's rulings on v3 PR 1's open questions (LEDGER.md "Open questions for PR
2"), recorded before building.
- W1 Privacy. Once the Relay URL is set, the About line (UiCopy.PRIVACY, README) becomes: "Chess uses
  the network only for Games with a friend: it sends their Moves to its Relay, with no name or
  account, and the Relay deletes them 30 days after the last Move. Puzzles and Games against the
  computer never leave this phone." The retention clause must match what relay/ does; the maintainer
  approves the final wording (a LEDGER item). ADR 0004 "The Relay is Chess's only network, and only
  for Correspondence Games" supersedes ADR 0003's no-INTERNET sentence and its last paragraph:
  (1) Chess declares INTERNET and talks to one host, the Relay, over HTTPS/WSS only. (2) It sends a
  request only while this phone holds a Correspondence Game or an open invite, or when the user
  creates or redeems a code. (3) It sends no names, accounts or telemetry, and the Relay keeps no IP
  logs (C6, H8); Puzzles and the computer stay fully offline. NoNetworkYetTest is replaced by a test
  that a Tool with an empty correspondence store makes no transport call and schedules no LightWork
  job.
- W2 One action, two entries. While a Move waits for confirmation (F11), the Menu offers "Send and
  offer draw". The phone holds the offer locally (not a second pending entry, V8), sends the Move,
  then sends the drawOffer once the Move is stored. Until the opponent moves, the Menu also offers
  "Offer draw" on its own. An offer refused because the opponent already moved: "Offer not sent".
- W3 No clock warning (V14, C2).
- W4 Strip copy, one line each (R4.16; every string in StripFitTest):
  - your move: "Your move · 2d" + Menu (one unit, rounded down: 2d / 5h / 40m; rounding SUPERSEDED by W12);
  - a Move chosen, not confirmed: its SAN + Send + Undo;
  - "Sending" + Menu, with the board locked;
  - "Not sent" + Retry + Menu;
  - "Their move · 2d" + Menu;
  - "Live · Your move" / "Live · Their move" + Menu (PR 3);
  - "Draw offered" + Accept + Decline (a Move also declines);
  - "Time is up" + "Claim win" + Menu (semantics label "Claim win on time"; the full label if it fits);
  - "Out of sync" + Menu, the Menu's first row "This game stopped: the two phones disagree.";
  - "Update Chess" + Menu, the Menu row "Update Chess to continue this game";
  - "Game deleted" + Menu (the Menu's first row SUPERSEDED by W13); "Seat lost" + Menu;
  - the invite page: "Expires in 47h" (a fresh invite now reads "48h", SUPERSEDED by W12) + Cancel (second tap "Tap again to cancel") + Menu;
  - a Result: R4.13's copy plus "You won on time" / "You lost on time", with Rematch + Menu;
  - "Rematch sent" + Menu; "Rematch?" + Accept + Decline;
  - a rolled-back claim shows "Not yet" for 5 s.
- W5 Keep 50 finished (V13). They show in the existing Games list, merged newest first, with the
  Opponent Label where the Level goes ("2026.09.28 · ABCD · Won"). Each kind keeps its own cap of 50
  in its own file. A finished Game stays in the Play a friend list only during V10's rematch window.
- W6 Screens:
  - The Menu entry "Play a friend", or "Play a friend · Your move: 2" when that count is above 0.
    (The entry SUPERSEDED by N1 and N5: it is Home's row, with the same words, and no Menu has it.)
  - The Play a friend page lists, in E7's order: your Move first, soonest time left first; then their
    move; then invites; then stopped Games. Rows look like "ABCD · Your move · 2d". The bottom buttons
    are "New game" and "Enter code".
  - At 5 slots (V13, F9), both buttons show lightened, with "Finish a game first".
  - New game: "Play as" White / Black / Random, "Days per move" 1 / 3 / 7 (default 3, C2), then
    "Create code".
  - The code shows as 8 Crockford characters, ABCD-EFGH, the largest text on the page and alone on
    its line. Below it: "Tell your friend this code. It works once, for 48 hours."
  - Enter code: one field on the LP3 keyboard, then "Join" (InviteCodes.normalize). Errors: "Not a
    code" / "No such code" / "Code already used" / "Try again in a minute" / "No connection".
  - The Opponent Label defaults to the first four characters (F11). The game's Menu adds "Rename",
    local and never sent.
  - Rematch is at the Result (V11). When the cap is full: "Finish a game first".
  - "Forget game", with the second tap "Tap again to forget", once the Game is over or stopped.
- W7 Background sync:
  - A periodic LightWork job every 1 h, only while some Game waits on the opponent (C2, V10).
  - A one-off C8 job for a pending entry, with Retry backoff.
  - syncAll also runs when the Tool opens and when Play a friend or a Correspondence Game comes on
    screen.
  - The WebSocket only while that Game's board is on screen, pinging every 5 s, closed in onAppPause:
    DEFERRED to v3 PR 3.
  - Nothing appears on the LightOS side. No push (F1).
- W8 The Relay URL is a committed constant, RelayConfig.URL, empty until the maintainer deploys (Light
  builds from the public commit).
  - Debug and emulator builds may override it with -Prelay.url, through generated source in the debug
    source set only.
  - While it is empty: no "Play a friend" entry, no LightWork job, and About keeps the old line.
  - Tests: the URL is empty or https:// (cleartext only to 127.0.0.1 in debug).
  - release-check.sh refuses a release that declares Chess's own INTERNET while the URL is empty.
    INTERNET is already merged in from the SDK, so the check keys on lighttool.toml's declared
    permissions.
- W9 Fix V9 now, before any deploy: the phone chooses its own seat secret (32 random bytes,
  base64url), sends it on redeem and join, and the Relay stores its SHA-256 (C3). A retry with the
  same secret is idempotent. Folded into protocol 1.0, since nothing is deployed and no Game pins a
  version: docs/protocol.md, relay/ with its tests, and the Kotlin client with its fake Relay. A lost
  create response is still accepted (the code expires in 48 h).
- W10 Also in PR 2:
  - A Game against the computer and Correspondence Games coexist, in separate files and owners.
  - The engine never touches a Correspondence Game: no Game Hint, no Book, no evals, and no Takeback
    (contradiction 6), enforced in code with a test.
  - mode.txt gains "friend" (D6, R4.10).
  - The user's Side at the bottom, no flip (G3). The screen-on rule is contradiction 4. The Moves
    page is allowed.
  - Back mid-send is safe (C8).
  - Copy for every Refusal in UiCopy + StripFitTest: CAP_REACHED "Finish a game first", NOT_SAVED
    "Couldn't save", NO_SUCH_GAME "Game deleted".
  - The Menu's "Your move: N" counts only Games that are the user's Move and not Stopped.
  - Before merge: a device check of LightWork in Doze on the LP3 (orchestrator, under the lease), and
    one two-phone Game in bad weather on the emulator plus a JVM fake phone.
- New CONTEXT.md terms: Days per Move (avoid: time control, clock); Time Left; Opponent Label;
  Pending Entry (avoid: queued move, outbox); Stopped (Out of Sync, Update Chess, deleted, Seat lost;
  avoid: frozen, broken). UI vocabulary for docs/domain-ignore.txt: Play a friend, Enter code, Forget
  game.

## v3 PR 2 (implementation, 2026-09-28)
Rulings the log didn't make, each the one most consistent with it, taken while building W1-W10.
- Y1 W9 on the wire: redeem sends `{ v, seatSecret }`, join `{ v, seatSecret, joinToken }`; the reply
  no longer carries the secret. The same code or token with the same secret answers `200` with the
  same Seat and `startedAt` however far the Game has gone; another secret gets `409 invite_used`; a
  missing or malformed secret, or the creator's own, `400 bad_request`. Create is unchanged (V9's lost
  create response stays accepted). SUPERSEDES V9's "open for protocol 1.1".
- Y2 The phone saves its chosen secret as a `PendingSeat` (in `correspondence.json`, `seats`) before
  sending; a Seat being taken holds a slot of the five, is shown on the page as "ABCD-EFGH · Not
  sent" (a tap sends it again), and is sent again by syncAll. A refusal drops it (but
  `rate_limited`); no answer keeps it. A rematch's join works the same, then appends rematchAccept.
- Y3 W8's override: `-Prelay.url` goes into the debug build type's generated BuildConfig
  (`RELAY_URL`); every other build type's is `""`, set in defaultConfig, and `RelayConfig.url` reads
  `BuildConfig.RELAY_URL.ifEmpty { URL }`. Chosen over a debug-only Kotlin class or @EntryPoint: main
  code can't name a class that release lacks, and an @EntryPoint runs after the first screen is made
  (LightSdkApplication launches it on Dispatchers.Main), so the first screen could miss the override.
  Cleartext: `src/debug/AndroidManifest.xml` merges a network security config allowing 10.0.2.2 and
  127.0.0.1 only; `OkHttpTransport` accepts http:// only to those and localhost (`RelayConfig.allowed`).
- Y4 W8's release check is its own step, `scripts/release-check.sh relay` (in `all`), not part of
  `apk`: lighttool.toml declares INTERNET now (W1) while the URL stays empty until the deploy, so
  `apk` (which pins INTERNET as declared) passes and `relay` refuses the release until the URL is set.
  The public-content scan allows `10.0.2.2`, the emulator's fixed host alias.
- Y5 W1's retention clause: relay/ moves a Game's deletion to 30 days after every append (any kind)
  and deletes an unredeemed invite at 48 h, so "30 days after the last Move" isn't exact. The line
  reads "the Relay deletes each Game within 30 days of the last thing either phone sent it." ADR 0003's
  "last paragraph" W1 names is read as its privacy sentence; its other paragraphs stand.
- Y6 The friend mode (mode.txt `FRIEND`, the enum's name as the other modes are written) makes the
  Play a friend page the Tool's first screen, as the game mode makes the Game's board. With the URL
  empty the friend mode shows Puzzles. The page's Menu is in the top bar: New game and Enter code
  leave the strip no room for it; that Menu offers Puzzles, Play the computer, Games and About.
  (SUPERSEDED by N2 and N7: the page opens over Home, and has no Menu.)
- Y7 A chosen Move's strip has four buttons (SAN, Send, Undo, Menu): W4 names Send and Undo, W2 puts
  "Send and offer draw" in the Menu while the Move waits, and StripFitTest holds it to one line. It is
  the only strip past contradiction 2's three. (AMENDED by N4: Menu is the mark, so the SAN sits
  beside two text buttons, Send and Undo, within contradiction 2's three.)
- Y8 Second taps (Cancel, Forget game, Resign) stand until the second tap or another page, as Resign
  does against the computer. "Tap again to cancel" shows next to Cancel alone: next to Cancel and
  Menu it needs two lines.
- Y9 "Draw: dead position" is a Correspondence Game's copy for `DrawReason.INSUFFICIENT_MATERIAL`
  (a dead position, or FIDE 6.9's claimant with a lone king): "Draw: insufficient material" needs
  three lines next to Rematch and Menu. The Games page's Review keeps R4.13's copy (next to Back it fits).
- Y10 Notices (5 s, W4): "Not yet" for a claim rolled back, "Offer not sent" for a held or pending draw
  offer the opponent's Move overtook (W2), and the Refusal's copy for any other refusal the Game's own
  state doesn't show. Refusal copy beyond W10's three: "Not allowed now", "This game stopped", "Not a
  code", "No such code", "Code already used", "Your friend joined", "Try again in a minute", "Update
  Chess", "No connection", "The game moved on", "Not yet".
- Y11 W7's jobs: `friend-sync` (periodic, 1 h) and `friend-send` (one-off). The periodic job is
  enqueued when some Game starts waiting on the opponent and cancelled when none does (the job itself
  cancels it when it finds nothing waiting). The one-off job is enqueued only after the user's own
  action leaves something unsent, never by a job (LightWork's REPLACE would cancel the running one); it
  returns Retry while something waits. No job is scheduled, and no request sent, while the store holds
  nothing to send or read (`Correspondence.hasWork`).
- Y12 Until v3 PR 3's live socket, a Correspondence Game's board syncs once a minute while it is on
  screen, the screen awake (contradiction 4) and the Game waiting on the opponent, so a reply shows
  without leaving the board. It stops with the screen timeout, on pause and off screen.
- Y13 W5's merge: Games against the computer carry only a date, so the Games page merges by date,
  newest first, and on the same date the Games against the computer come first.
- Y14 W10's engine boundary is held by BoundaryTest on the source: no `correspondence/` or `Friend*`
  file names the engine, the Book, the Level player, GameOwner, GameFlow, evals, Hints or Takeback,
  and `FriendButton` has no Hint, Takeback or Move now. The same test replaces NoNetworkYetTest: only
  FriendOwner constructs the client, after the URL check, and no Puzzle or computer code names it.

## v1 smoke fixes (orchestrator, 2026-09-29)
- S1 Reconciles A9 with D7. A9 asked About for "per-puzzle lichess.org/training/<id> as text"; D7,
  which built About, lists only the Lichess CC0 credit and dump date, and About is one page for the
  whole Tool, so no screen showed a Puzzle's id. RULING: About keeps D7's credit unchanged; the
  per-puzzle part of A9 moves to the Menu, the one screen opened from a Puzzle: a plain row under
  About, "Puzzle <id>" over "lichess.org/training/<id>", for the Attempt on screen (a Missed replay's
  own id). Text only, never a link: the phone has no browser and Chess never uses the network (D5).
  The address gets its own line because Android broke it at a slash when it shared one with the id
  (emulator); each line fits 360 dp whole with the widest id (`StripFitTest`). (The placement
  SUPERSEDED by N8: the lines end About.)
- S2 The save file keeps up with every result (A8, "written on every result"): a background save
  follows every change to what the file keeps except the Moves of the Attempt on screen, which is
  the Attempt's state, `done`, `solutionShown` and "Try again". Seen on the emulator: after a wrong
  Move, the Failed result reached the file only in onAppPause, so a kill at the result relaunched
  into the Puzzle mid-way. A relaunch in Try Mode after a wrong Move reads "Try again", as before the
  kill. Scoring stays on the Attempt's own transitions (A3), so a relaunch never scores twice.
- S3 Back is page-aware: each Menu page is its own screen on the SDK's back stack, so system Back
  from Player Rating, Missed or About returns to the Menu, as the arrow does, and the Menu returns to
  the Puzzle. System Back can't be intercepted by a screen (PLATFORM.md), which is why pages that were
  only state inside one screen skipped the Menu. Back from the puzzle screen still closes the Tool.
  (That last sentence SUPERSEDED by N2: it goes to Home.)

## Pieces: an original CC0 set (2026-09-29)
- P1 SUPERSEDES R1.7's piece source (cburnett), D7's cburnett credit, and F11's "cburnett only" and
  "Icon: gray cburnett knight" (one set, now this one; see the icon below); the rest of R1.7 stands,
  including the conversion to ImageVector at build time (`scripts/build-pieces.py`). RULING: the
  pieces are Chess's own drawings, twelve SVGs in `art/pieces/` released under CC0 1.0 Universal
  (`art/pieces/LICENSE.txt`), not derived from cburnett or any other set. The owner approved the
  design as drawn; a change to a drawing is a design change and goes back to the owner.
  - Style: LightOS's glyph style, geometric and flat on a 45 × 45 viewBox, no shading or texture.
  - One silhouette per piece. Black is that silhouette, solid, with thin white cuts where needed (the
    bishop's slot, the knight's eye).
  - White is its outer-line twin: the same silhouette filled white over a wider black stroke painted
    first, with thin black cuts, so a white piece keeps an edge on the light square as well as the
    dark one. The converter keeps each file's path order for this; `PieceVectorsTest` pins it.
  - Credit: one line, "Pieces: original drawings made for Chess, released under CC0 1.0 (no rights
    reserved).", in NOTICE, the README and About (`assets/about/notices.txt`). With cburnett gone,
    About no longer reproduces a BSD licence. (Wording SUPERSEDED by P2, for two sets; this set now
    lives in `art/pieces/geometric/`.)
  - The Tool icon is unchanged: Light's plugin generates the manifest with no `android:icon`,
    `lighttool.toml` has no icon field, a hand-written manifest fails the build, and LightOS lists
    Tools by label. When Light offers a way to set one, it is this set's knight (the owner has a
    white-on-black and a black-on-white version), not a cburnett knight.

## v1 review follow-ups (orchestrator, 2026-09-29)
- V1 A save at a result whose Moves don't replay against the Solution (only a damaged file does
  this) resumes at the result, with no Moves on the board, its state and the result strip's delta
  kept. It no longer restarts from the setup Move, where finishing it again scored or recorded a
  Solved or Hinted Attempt twice. An Attempt under way still restarts from the setup Move (F1).
- V2 An Attempt state this build doesn't know, in a Missed or history row, reads as Failed
  (`coerceInputValues` needs a default), so a newer build's file stays readable. An older build that
  rewrites the file writes Failed back: a new state therefore needs a new schemaVersion and its own
  migration, as the compatibility rule in `PuzzleData` implies.
- V3 Band files are read ahead on a background thread instead of choosing the next Puzzle
  asynchronously: the choice stays at the result (D1) and the strip behaves as before. Read ahead:
  the Bands for the Player Rating after a win, a loss or no change, when a rated Attempt starts or the
  rating changes; every Missed Puzzle's line when the Missed page opens. The seed screen's pick is
  not read ahead (once per install or Reset rating). A tap that outruns the read ahead reads the file
  on the main thread, as before.
- V4 Assets: the SDK reads them only through `SealedLightContext.readAsset`, which holds the
  screen's activity; `Context`, `LocalContext` and casts to it are blocked. The application's assets
  are reachable only around the SDK (an `AndroidView` factory's context, or Compose's
  `LocalResources`), which Chess doesn't do. RULING: the owner reads through the latest screen that
  asked for it, so a relaunch releases the old activity; the owner holds at most the latest one. A
  real fix needs an application-level asset reader in Light's SDK (a question for Light).
- V5 The stage clock restarts only when the Attempt on screen changed, and a slide is cleared once
  it has played or once its Move is no longer the latest (`slideAfter`, `restartsClock`).

## Pieces: two sets, the player's choice (owner, 2026-09-29)
- P2 EXTENDS P1 to two sets and SUPERSEDES F11's "no v1 settings" for this one choice. RULING: the
  player chooses the Piece Set (CONTEXT.md) from two, both Chess's own drawings under CC0 1.0 in
  `art/pieces/` (one `LICENSE.txt` for both): geometric (`geometric/`, P1's set) and rounded
  (`rounded/`, drawn from circles, capsules and rounded rectangles; `rounded/tools/` generates its
  SVGs). The owner approved both as drawn; P1's design rules hold for both, and a change to either
  goes back to the owner.
  - Geometric is the default, for a new player and for an existing save.
  - The Menu shows the choice as a row, "Pieces · Geometric", between Missed and About; a tap moves to
    the next set ("Pieces · Rounded", then back) and stays on the Menu. The Puzzle id row stays the
    Menu's last row (S1). The Menu page doesn't scroll, so the row takes no wheel key (R4.17).
    (The placement SUPERSEDED by N1 and N5: the row is Home's, between Games and About, and no Menu
    has it; the Puzzle id by N8; the wheel by N10, as Home scrolls on the LP3.)
  - The board and the promotion picker draw the chosen set. It changes nothing about play.
  - Saved: `pieceSet` in `puzzles.json`, v1's only save file, written at once like every other kept
    change (S2). It outlasts a relaunch and Reset rating (F5 resets only the Player Rating). A file
    without it, or with a set this build doesn't know, reads as geometric (coerceInputValues, as V2).
    No schemaVersion bump: the field is only added, and no field an earlier schema knows changes
    meaning. An older build ignores it and, if it rewrites the file, drops it, so the player sees
    geometric again: a lost look, never a wrong result. V2's reason for a bump (an older build
    writing a different meaning back) doesn't arise. When v2 adds its own save file (D6), the Piece
    Set may move to a Tool-wide one, with a migration.
  - Credit: P1's line, reworded for two sets and identical in NOTICE, the README and About
    (`assets/about/notices.txt`): "Pieces: original drawings made for Chess (two sets), released
    under CC0 1.0 (no rights reserved)." `ToolMetadataTest` checks all three. `LICENSE.txt` is titled
    "Chess pieces", so it doesn't read as a work of Light's.
  - `scripts/build-pieces.py` converts both sets into `PieceVectors` and now fails loudly on numbers
    after Z (it looped forever) and on any attribute it doesn't understand (it dropped them);
    `--self-test`, which `--check` runs in CI, covers both.

## Forward merge: v2 on v1 0.1.0 (orchestrator, 2026-09-29)
v2 (feat/v2-play) takes main's v1 release, the smoke fixes (S1-S3), the new pieces (P1, P2) and the
review follow-ups (V1-V5). Two rulings reconcile them with v2's Menus and save files:
- M1 Reconciles P2's placement with v2 (D6's second save file, R4.11's two Menus). RULING: the Piece
  Set stays one Tool-wide choice, and every board draws it: the puzzle screen, the game screen, a
  finished Game from Games, and each one's promotion picker. It stays in `puzzles.json` (P2's
  field, its compatibility rule unchanged); `games.json` doesn't copy it, and the game screen and
  the Games Review read it from the puzzle owner. P2's "may move to a Tool-wide file, with a
  migration" is left for a second Tool-wide setting: one field doesn't need a third file. The row,
  "Pieces · Geometric", sits just above About in both Menus: the puzzle Menu reads Player Rating,
  Missed, Play the computer, Pieces, About, then the Puzzle id row, still last (S1); the game Menu
  ends New game, Games, Puzzles, Pieces, About. A tap moves to the next set and stays on the Menu.
  (The row's place SUPERSEDED by N1 and N5: it is Home's, and neither Menu has Pieces, Games,
  Puzzles, About or the Puzzle id, N8. The Tool-wide choice and every board drawing it stand.)
- M2 Applies S3 to v2's pages: New game, Games and Moves are each their own Menu screen on the back
  stack, so Back (the arrow or the system's) goes one page up to the Menu. Start leaves the Menu for
  the board, as a reset or a Missed replay does. New game opened from the Result's Next is its own
  screen over the board, so Back returns to the board. A finished Game opened from Games goes back to
  Games. (AMENDED by N2 and N5: Games is Home's page, not the Menu's; New game opened from Home is
  replaced by the board on Start, and sits over no board until then.)
- M3 V4 holds for the game owner too: `GameOwner` reads the Book through the latest screen that asked
  for it, as `PuzzleOwner` reads the Pack, so a relaunch releases the old activity.
- M4 Fixes M1 on a cold start into the game mode (PR 2 review): the game screen waited only for
  `games.json` and drew the default set until the puzzle owner had read `puzzles.json` and the first
  Puzzle's Band (about 250 KB), and the game Menu had no Pieces row until then. RULING: the puzzle
  owner reads `puzzles.json` first and publishes its Piece Set before any Band is read; the game
  screen draws no board until it has both its file and the Piece Set, and the game Menu and the Games
  Review read the set from there. Only a small file stands before the first frame, as `games.json`
  already did, so the Game isn't slowed; waiting for the whole puzzle session would have put the Band
  read in front of it. A tap on Pieces before the first Puzzle is read is kept and applied to it.

## Forward merge: v3 on v2 and v1 0.1.0 (orchestrator, 2026-09-29)
feat/v3 takes feat/v2-play after its forward merge above (M1-M3). These were M4 and M5 on
feat/v3; main's M4 above (a v2 review fix) reached main first, so they are M5 and M6 here.
- M5 M1 reaches Play a friend: the Correspondence Game's board draws the Piece Set, and its Menu
  has the Pieces row just above About; the Play a friend page's Menu (MenuScreen) has it above About
  too, as the puzzle and game Menus do. Still one choice, kept in `puzzles.json`. (The Pieces rows
  SUPERSEDED by N1, N5 and N7: the row is Home's, and the Play a friend page has no Menu.)
- M6 M2's pages: the Play a friend Menu's Play the computer opens the new-game page as its own
  screen when no Game is in progress (R4.11), and Games and About are their own screens (S3). The
  Correspondence Game's own Menu (FriendScreen: Moves, Rename) still keeps its pages as state in one
  screen, so system Back there skips to the board: S3 isn't applied to it yet (LEDGER.md). (FIXED by N6.)
  (The Play a friend Menu SUPERSEDED by N7: Home opens the new-game page, Games and About.)
- M7 M4 reaches Play a friend (the merge of main after 0.2.0): the Correspondence Game's board and
  its Menu's Pieces row read the Piece Set the puzzle owner publishes before any Band, as the game
  screen does, and so do the Play a friend page's Menu and the game Menu's row. (The rows
  SUPERSEDED by N1 and N5: Home's Pieces row reads the set the same way, waiting for it, M4.)

## Stockfish calibration (2026-09-29; docs/levels.md "The Stockfish gauntlet")
- Facts: Stockfish 19 (Homebrew; 17.x no longer offered), Mac only, run as a UCI process from
  `LevelCalibrationTest` (`-Dcalibrate=stockfish`), never in the APK, the runtime classpath or the
  repo; the mode skips when no `stockfish` is on PATH. No cutechess-cli: our rules core referees.
  `UCI_LimitStrength`, `UCI_Elo`, Threads 1, Hash 16, a fixed 1,000,000 nodes per Move (the pick
  depth, 1 + level, was reached on every Move up to 2700 and on 97.9% at 3000). Our Levels as on the
  LP3 (Level 8 = 1,674,000 nodes); the 20 openings × 2 colours; seeded on our side, clock-seeded on
  Stockfish's.
- Measured (1,280 Games, 80 per pairing, 4 h 40 min): Level 1 below 1320 (9/80 against 1320; ~960
  extrapolated), Level 2 1263 [1194, 1330], 3 1472 [1410, 1535], 4 1738 [1679, 1796], 5 1984 [1920,
  2047], 6 2390 [2326, 2453], 7 2628 [2565, 2692], 8 2948 [2888, 3008] (95% likelihood intervals,
  settings fixed at their labels).
- Contradiction: B2's "approx." labels need a consistent fit, and this one isn't. Seen from our
  Levels, the settings' steps are 463, 233 and 245 Elo from 1320 to 1700, 2100 and 2500, not 380,
  400 and 400 (free fit: 1700 +143 off its label, 2500 -179), and Level 5's scores against 1700 and
  2100 fit no single rating (p = 0.0003). The test, set before the fit: every Level at p >= 0.01 and
  every shared setting within ±100 of its label. Both parts fail.
- RULING: no Elo labels. The Level picker stays numbers only (v2 PR 3); B2 and B8 stand, and the
  estimates stay in docs/levels.md, not in the UI.
- Caveat for a rerun: the settings were calibrated at 120 s + 1 s (several million nodes per Move).
  A side run suggests they get stronger with the budget (Level 2 against 1320: 10/24 at 100K, 4.5/22
  at 3M), though not significantly at 20-24 Games a cell. A 3M-node gauntlet (about 15 hours with 8
  threads) is the next thing to try before giving up on UCI_Elo labels.

## The Relay's address (owner, 2026-09-29)
- W11 The Relay's URL, which W8 left empty until the deploy, is `https://chess-relay.yarosz.com`:
  `RelayConfig.URL`, committed. It is a Worker custom domain on the maintainer's `yarosz.com` zone, in
  the same Cloudflare account as the Worker, bound to the `chess-relay` Worker by the maintainer's
  infrastructure code, not by wrangler: `relay/wrangler.jsonc` has no `routes`, and `wrangler deploy`
  uploads the Worker and its Durable Object migration only, so it never creates DNS. Why: the Tool
  can't change the address it ships with, and a subdomain of a domain the maintainer owns survives a
  move of account or host, which a workers.dev address (named after the account) doesn't. It is
  first-level (`chess-relay.`, not `relay.chess.`), so the zone's Universal SSL certificate covers it
  with nothing to buy. `workers_dev` is `false`: one public address, the one the Tool ships. A debug
  build still takes `-Prelay.url` for a local Worker (W8, Y3); release,
  and a debug build without it, use this URL. The URL is set before the deploy, so a release waits
  on `/health` answering there as well as on `release-check.sh relay` (RELEASING.md).

## Captured Pieces under the board (owner, 2026-09-29)
- P3 RULING, from the owner's review of a mock-up drawn on the LP3's layout: every board in a Game
  shows the Captured Pieces (CONTEXT.md) in one row under the board: the game screen against the
  computer, a Correspondence Game's board, and Games Review. A Puzzle's board never does (a Puzzle
  starts mid-Game, so captures mean nothing there), and its strip is unchanged.
  - Place: the strip's top band, the gap between the board's bottom edge and the strip's text.
    (SUPERSEDED by E4: the row sits at the left of the action row, beside the buttons; and E4 by E7:
    the row is the action row's top line, the drawings 3 dp under the board, across its width.) At the
    left end the pieces the Side at the bottom of the board has taken, at the right end the other
    Side's, each end growing inwards from the board's edge; a flipped board swaps them. Both ends read
    pawn, knight, bishop, rook, queen from left to right, as the mock-up drew them, so the left end has
    its pawns at the edge and the right end its queens. Pieces of one kind overlap like a fanned hand,
    the inner piece over the outer. Every captured piece is drawn; nothing is summarised, and the row
    never wraps (`CapturedRowTest` proves the widest row, fifteen pieces at each end with a +103
    Material Lead, keeps its two ends apart).
  - Sizes (`CapturedRowLayout`), from the mock-up's 52, 17 and 50 px at 3 px per dp: drawings 17 dp,
    5.5 dp from one piece to the next of its kind, 16.5 dp to the next kind, 3 dp below the board.
    (AMENDED by E8: drawings 15 dp, steps 5 and 14.5 dp, the mock-up's proportions; 3 dp holds.)
  - The Material Lead, "+7" from the material on the board (P1 N3 B3 R5 Q9, so a promotion counts), in
    LightOS Superfine and the secondary content colour, just inside the leading Side's end; nothing
    when the material is even.
  - A captured black piece is the white drawing with its body gray (`Shades.CAPTURED_BLACK_BODY`,
    150, the mock-up's #969696): solid black would vanish on the black ground, and the black outer
    line keeps overlapping pieces apart, as it does for white ones. `scripts/build-pieces.py` writes
    these twins for both Piece Sets (P1's rule that a change to a drawing goes back to the owner is
    kept: the twin changes only a fill colour, and the owner approved it in the mock-up), and
    `--check` covers them. The row uses the player's Piece Set.
  - What counts: the Game's Moves up to the Position shown (Review follows the Ply on screen; a
    Correspondence Game's chosen Move, not yet sent, counts as shown), en passant included. A piece
    taken is the one its capture removed from the board (en passant's pawn included), so a promoted
    pawn taken later is the piece it became, and the pawn it came from is never counted.
  - The strip (SUPERSEDED by E4 with the strip's band; the boards have no strip): on every Game
    board the status and buttons centre in the part of the strip below the
    row's band (a one-line status 10 dp lower than in a Puzzle), and a status's two lines are set
    closer (the room below the row divided by two, just under Copy's font size) so a two-line Result
    (R4.16) still fits there; the buttons' padding above and below is 4 dp there, not 8. The strip
    keeps its size and place, so the board never moves. A Puzzle's strip is exactly as before.
  - The band is reserved from a Game's first Position, before anything is captured (owner's choice,
    2026-09-29, SUPERSEDES this ruling's first draft, which lowered the text only at the first capture):
    the text never moves, neither when the first piece is taken nor when Review steps back across that
    Ply. The pieces simply appear in the band once there is a capture.
  - Accessibility: the row reads, in its own order, "Captured by White: two pawns, a queen. Captured by
    Black: a knight. White is ahead by 7." ("Material is even." when neither leads), from `UiCopy`.

## Play a friend: Time Left and a deleted Game (orchestrator, 2026-09-29; LEDGER "FOLLOW-UPS (v3 review)")
- W12 SUPERSEDES W4's "one unit, rounded down" for Time Left and the invite's expiry. Rounding down
  put a fresh Game on a unit's edge: a Deadline falls a whole number of days after a Move (C2), so
  a 3-day Game read "3d" on one phone and "2d" on the other a few seconds later, or with the phones'
  estimates of the Relay's time (V14) a few seconds apart. Rounding up has the same edge the other
  way (3d and a second reads "4d"). RULING: hours to the nearest (half up) while under 48 are left,
  days to the nearest (half up) above; the last hour in minutes rounded up. "3d" from 2d 12h to
  3d 12h, so a fresh 3-day Game reads "3d" on both phones through half a day of skew; "2d" from
  47h 30m; then "47h" down to "1h" (a fresh 1-day Game reads "24h", in the invite's "48h" style);
  then "59m" down to "1m". Days only from 48 hours because day-scale rounding overstates by up to
  12 hours ("2d" at 1d 12h would be a real hazard for a player planning to move "the day after
  tomorrow"); below 48 hours, wherever a misread can lose the Game, it overstates by at most 30
  minutes, and "3d" always means at least 2d 12h. "0m" shows only once the Deadline has passed,
  which is exactly when "Time is up" and "Claim win" show (the Relay's stamps still decide, and a
  claim it refuses is rolled back as "Not yet", V14). The invite's "Expires in 48h" rounds its hours
  the same way (it stays in hours: an invite lasts 48). The input is clamped to a year first, so a
  nonsense Deadline can't overflow. `UiCopy.timeLeft`, `UiCopy.expiresIn`; `FriendPagesTest` holds
  the boundaries.
- W13 A Game the Relay deleted ("Game deleted", GONE, C6) can never change again, so its row on the
  Play a friend page opens the Game's Menu, not its board: the Menu's first line says why to forget
  it, in the out-of-sync line's style (V13): "This game was deleted. Forget it to free its place."
  (the row keeps "Game deleted"; StripFitTest holds the Menu's stop lines to two lines each), and
  Forget game (second tap "Tap again to forget", W6, Y8) is on it with Moves and Rename. Before,
  Forget game was reachable only through the board's Menu button, and the list itself offered
  nothing. Forget is local: it removes the Game from `correspondence.json` and sends nothing
  (the Relay has nothing to delete), so it works offline. The other Stopped Games (Out of Sync,
  Update Chess, Seat lost) still open their board: the Game still exists on the Relay.
- Implementation: `FriendJobs.run` lets a `CancellationException` through (LightWork stopping the
  job ends it as cancelled), instead of logging it as a failed sync. `TimeoutCancellationException`
  is a subclass, so a future `withTimeout` around a request must catch its timeout as a failure
  (Retry, C8) before this catch.

## Relay limits and plain HTTP (v3 review follow-ups, 2026-09-29)
Implementation rulings for three follow-ups of the v3 review (LEDGER). Applied in relay/ and
docs/protocol.md; the Tool is unchanged (it speaks HTTPS only, RelayConfig.URL, W11).
- L1 The rate limits key on the client address, `CF-Connecting-IP`, as `clientKey`
  (relay/src/limits.ts) reads it: an IPv4 address whole; an IPv6 address by its /64, the first four
  hextets after expanding `::`, in lowercase hex without leading zeros (`2001:db8:0:0::/64`); an
  IPv4-mapped address (`::ffff:1.2.3.4`) as its IPv4 address; anything missing or unreadable under
  one shared key. Why the /64: a subscriber holds at least a whole /64, so the full address let one
  client pick a fresh budget per request. The /64 alone does not close that hole: a holder of a
  larger delegation still gets one budget per /64 in it, which L5 bounds. AMENDS F11 and H7 ("per
  client IP" is now per client address in this sense). The shared fallback key means a Cloudflare
  fault that drops the header would limit everyone together, which fails safe; the header is always
  set on the deployed Worker. Every limit is approximate: Cloudflare's binding counts per location
  and is eventually consistent.
- L2 `POST /v1/games` is limited like a redemption: its own binding `CREATE_LIMITER` (namespace
  1002), 10 per 60 s per client address (L1), counted before the body is read, `429 rate_limited`
  with `Retry-After: 60`. Why 10: a phone creates one Game per invite or rematch offer and holds at
  most 5 (E7, F9, V13), so 10 a minute covers all five at once plus a retry of each after lost
  responses (W9: a lost create leaves an unheld invite), while a script can no longer fill the
  Relay's storage with Games at request rate. Several phones behind one carrier NAT share an IPv4
  budget; creating a Game is rare enough that 10 a minute is still ample. The Tool already shows
  `rate_limited` as "Try again in a minute" on every request.
- L3 The Worker refuses plain HTTP: a request whose URL scheme is `http:` gets
  `426 upgrade_required` ("Use HTTPS: https://<host><path>") before any other check, on every
  endpoint, `/health` included. 426 is the status RFC 2817 defined for "switch to TLS", and reusing
  the existing code keeps the protocol's error table (and the Tool's `ErrorCode`, which
  ProtocolTest checks against it) unchanged; a new `https_required` code would need a Tool
  release to name it. No redirect: a 301/302 lets a client turn a POST into a GET and follow it
  silently, and whatever secret the request carried has already crossed the network in the clear,
  so the client should fail loudly. Local development is told apart by host, not by a variable:
  plain HTTP is served only when the host is `localhost`, `127.0.0.1`, `[::1]` or `10.0.2.2` (the
  emulator's alias for the host), the set the Tool's debug build may use (Y3, W8). The deployed
  Worker is routed only by its custom domain (W11), so those hosts never reach it, and there is no
  setting to forget or to leave on in production. The vitest pool uses `https://relay.test`.
  Complements, not replaces, Always Use HTTPS and HSTS on the zone, which remain the maintainer's
  infrastructure choice.
- L4 `/v1/sync` checks each `seatSecret` is 43 base64url characters, like redeem and join (W9);
  a malformed one is `400 bad_request` for the whole batch, since no phone can hold such a secret.
- L5 (code review of L1) Redeem and create are each also limited per IPv6 /48: bindings
  `REDEEM_WIDE_LIMITER` (namespace 1003) and `CREATE_WIDE_LIMITER` (1004), 100 per 60 s, keyed by
  `wideClientKey` (the first three hextets, `2001:db8:0::/48`). A request must pass both its /64
  limit and its /48 limit; the /64 is checked first, so a request it refuses is not charged to the
  /48, and one busy /64 spends at most 10 of its /48's 100 a minute. IPv4, IPv4-mapped and
  unreadable addresses key the wide limit exactly as L1 does: the per-client limit already counts
  the whole address, so the wide one never binds there, and one code path is simpler than a skip.
  Why: ISPs delegate a /56 or /48 by DHCPv6-PD and free tunnel brokers hand anyone a /48, so under
  L1 alone a /48 holder had 65,536 /64s, 655,360 redeem guesses a minute. Against the 2^40 Invite
  Codes that is about 1.9 billion guesses over an invite's 48 hours, 1 in 583 of hitting a given
  open invite. With L5 a /48 gets 100 guesses a minute, 288,000 in 48 hours, 1 in 3.8 million per
  open invite (a lone /64: 28,800, 1 in 38 million). The odds grow with the number of open invites
  and the Cloudflare locations an attacker reaches, and a holder of many /48s or many IPv4
  addresses is not bounded; that is accepted for a Relay with no accounts. Create gets the wide
  limit too although it is a storage-fill vector, not a guessing one: without it a /48 could create
  655,360 Games a minute, which undoes L2's "not at request rate"; with it, at most 288,000
  unredeemed Games per /48 are alive at once, each deleted after 48 hours. Why 100: ten /64s' worth,
  so a /48 that is one site (a household, an office) with several phones behind it is never the
  limit before each phone's own /64 is.

## Navigation D (owner, 2026-09-30)
The owner chose navigation model "D" after a UX review, a fresh-eyes diagnosis, a look at lichess,
chess.com and LightOS, and interactive mocks. The diagnosis: Chess had no fixed home. Its root screen
was whichever mode was used last (D6, R4.10, Y6), so system back from a Game against the computer
closed Chess. "Menu" meant four different lists that mixed actions, places and settings (B5, M1, M2,
M5, Y6). And the three activities were each built differently. The research: lichess and chess.com
keep a game's actions apart from the app's navigation; LightOS is a vertical list plus back; a
hidden gesture is found about half as often as a visible control; and a swipe on the board would
collide with drag Moves and with the system's edge back. The rule of D: LEFT always leaves (toward
Home), RIGHT always opens this board's actions, and a fixed Home list goes places. No gestures.
- N1 Home. SUPERSEDES D6's "open on the mode LAST USED" as the root, R4.10's and Y6's first screen,
  and A10's "visible Menu (rating/history, About)". RULING: the root screen is a list titled
  "Chess", with no back arrow; system back from it closes Chess. Its rows, in order: "Puzzles",
  "Player Rating · 1727?", "Missed · 3", "Play the computer", "Play a friend" (with "· Your move: 2"
  while that count is above 0, W6; absent while the Relay URL is empty, W8), "Games", "Pieces ·
  Geometric", "About". The rating has its own row, directly under Puzzles, as on the puzzle Menu
  before: the number shows once, on the row that opens it, the way a LightOS list puts a value on
  its own row. "Puzzles · 1727?" over "Player Rating · 1727?" would show it twice. Puzzles opens
  the Puzzle board. Player Rating, Missed, Games and About open their pages over Home. Play the
  computer keeps R4.11's behaviour: it returns to the Game in progress, else it opens the new-game
  page. Games is always there, Puzzles included, and so are Pieces (P2) and About. `HomeRows`
  holds the rows as data (`HomeTest`).
- N2 Launch and depth. KEEPS A10 and D6's resume. SUPERSEDES S3's "Back from the puzzle screen
  closes the Tool". RULING: Chess opens on the last-used place (`mode.txt`, R4.10's file and words),
  pushed on top of Home. A cold start in Puzzles opens straight into the current Puzzle, with Home
  underneath, and back from the Puzzle goes to Home. A cold start in the computer mode opens the
  Game's board (R4.11: the last finished Game at its Result when none is in progress), and the
  friend mode opens the Play a friend list. With the Relay URL empty it opens the Puzzle instead
  (Y6). Opening a place from Home writes its mode; the pages (Player Rating, Missed, Games,
  About) don't. A Missed replay and Reset rating write Puzzles. Every place is at most root, then
  place, then detail: Home, then the Puzzle board; Home, then the computer's board (the new-game
  page, when Home opens it, is replaced by the board on Start, so back from the board goes to Home,
  not to the form); Home, then Play a friend, then a Correspondence Game's board or an invite;
  Home, then Games, then a replayed Game; Home, then Missed, Player Rating or About. A Missed replay
  and Reset rating replace their page with the Puzzle board, as Start replaces the new-game page.
  Leaving the computer's board for Home stops its search until the board shows again (R4.11's
  "Puzzles" rule, which it replaces); the board's Menu and its pages don't stop it (contradiction 3).
  A page opened from Home, New game included, sits over no board, so it never starts the
  computer's search; only one opened from the board or from a page over it does (`MenuScreen`'s
  `overGame`). A Game Hint being found when the board leaves is dropped, and one on show goes, so the
  strip offers Hint again on return (`GameOwner.pause`). `Navigation` holds the launch stack and
  where each Home row leads, and `HomeNavigator` what opens over what and what replaces a page once
  done (`HomeTest`, `GameOwnerTest`).
- N3 The back arrow on every board. SUPERSEDES the Games replay's "Back" text button (v2 PR 4) and
  R1.8's "the strip is exactly as wide as the board". (The arrow's place in the strip SUPERSEDED by
  E1: every board's arrow is in LightOS's top bar. That every board has it, in every state, as
  `goBack` labelled "Back", holds.) RULING: every board's strip (a Puzzle, a Game
  against the computer, a Correspondence Game, a replayed Game) has LightOS's back arrow
  (`LightIcons.BACK`) at its left, in every state, drawn where LightOS's top bar draws it: one grid
  unit in from the screen's edge, two grid units square, so its ink covers pixels 48 to 82 of 1080
  on the LP3 (the last inked pixel is 82; its right edge is at x = 83 px, 27.7 dp), as measured on
  LightOS screenshots, centred on the strip's text line. It does what system back does (`goBack`),
  and its label is "Back". The strip now spans the screen: the status starts 2.8 grid units in
  (37 dp, 112 px), inside the board's content area. The target covers the strip's height and is 48
  dp wide from the screen's edge (`StripLayout.backTarget`), as the mark's is: it runs about 11 dp
  over the start of the status, which takes no touches, and the ink stays where it is. The Captured
  Pieces row (P3) stays aligned with the board.
- N4 The Menu mark. SUPERSEDES the strip's "Menu" text button (R1.8, contradiction 2, Y7). (On the
  boards, the mark's place in the strip, centred on its x-height, SUPERSEDED by E1: it is the top
  bar's right button, centred on the bar's middle line. Its squares, sizes, label and 16 dp ink end
  hold, and the invite page's strip keeps it as ruled here.) RULING:
  the Menu is three solid squares, LightOS's "more" mark as the Album tool draws it: each about 2.67
  by 2.33 dp (8 by 7 px on the LP3), 9.33 dp (28 px) apart centre to centre. They are drawn as
  shapes, not typed as a font's full stops, because the emulator (Roboto) and the LP3 (Akkurat)
  draw full stops differently. They are centred on the strip text's x-height, measured from the font
  in use, not on the baseline. The mark sits at the strip's right, its ink ending 16 dp from the
  screen's edge (the back arrow's mirror), with a 48 dp wide target the strip's full height. Its
  label stays `UiCopy.MENU_DESCRIPTION` ("Open the Menu"), so a script that taps "Menu" still finds
  it. It replaces every strip "Menu" button: the computer's board, a Correspondence Game's board,
  the invite page. Text buttons sit to its left, at most three (contradiction 2; Y7's four are now
  SAN, Send, Undo and the mark). The last one's label ends where the target starts, its right
  padding under the target, which takes those touches: "Rematch?" beside Accept and Decline needs
  those 8 dp (N9). Without the mark, the last label's ink ends 16 dp from the edge, where the
  mark's would.
- N5 A Menu holds only this board's actions. SUPERSEDES B5's, M1's, M2's, M5's and M7's Menu rows
  for places and settings, P2's and M1's Pieces row in every Menu (it is on Home), S1's Puzzle id
  row (N8), and Y6's and W6's Play a friend Menu. RULING: the computer's Menu: Offer draw, Resign,
  Takeback, Flip board, Moves, Think Time (Level 8), New game, each as before (G1's lines, the
  second taps). A Correspondence Game's Menu: why it stopped (the halt line, W13), Send and offer
  draw or Offer draw (or its lightened line), Resign, Moves, Rename, Forget game (over or stopped).
  An invite's Menu: the halt line, Rename, Forget game (stopped). None has Puzzles, Play the
  computer, Play a friend, Games, Pieces, About, Player Rating or Missed. The Puzzle board has no
  Menu: its strip is the arrow, the status and Hint, Solution, Next or Latest as before. (Since E1
  and E2: its top bar is the arrow and the status, its action row the buttons.) `GameMenu`
  and `FriendMenu` hold the rows as data (`MenuTest`).
- N6 S3 everywhere. FIXES M6's open issue. RULING: every Menu page is its own screen on the back
  stack, so system back pops one level: a Correspondence Game's Moves and Rename now open as screens
  over its Menu, as the computer's Moves and New game already did (M2). A second tap ("Tap again to
  resign", "Tap again to forget") is a row that changes in place, not a page, so it needs no screen.
  New game on the Play a friend list becomes its invite in place once the code is made: the invite
  replaces the form, so back from the invite goes to the list.
- N7 The Play a friend list. SUPERSEDES W6's and Y6's top-right "Menu". RULING: a LightOS top bar
  with the back arrow (to Home) and the title "Play a friend", and no Menu: Home has everything it
  offered. Its strip (New game, Enter code) is unchanged, and so is its order (E7). A deleted
  Game's row still opens that Game's Menu (W13).
- N8 About carries the Puzzle's id. SUPERSEDES S1's placement (the Menu's last row), keeps A9's
  intent. RULING: About ends with the Puzzle last shown: "Puzzle 00sHx" over
  "lichess.org/training/00sHx", as text (D5, D7). About is opened from Home, whose Puzzles row
  opens that Puzzle.
- N9 Copy. RULING: a draw offer's strip reads "Draw?" (`UiCopy.DRAW_QUESTION`). The Play a friend
  row keeps "Draw offered". The draw offer's and the rematch offer's strips gain the Menu mark:
  "Draw? · Accept · Decline · Menu" and "Rematch? · Accept · Decline · Menu". `StripFitTest`
  measures every strip with the arrow and the mark, at the LP3's width. (On the boards, E1 and E2:
  "Draw?" or "Rematch?" is the top bar's title beside the mark, Accept and Decline the action row.)
- N10 The wheel. KEEPS R1.9, F2, F3 and R4.17 on every board and page. RULING: Home is a list page.
  While it scrolls (on the LP3 its eight rows under the top bar need more than the app area's 389
  dp), it takes the wheel one row per detent and takes every event, a click included (F3). If
  all its rows fit, the wheel stays with LightOS, as it did on the puzzle Menu (P2).

## Layout E (owner, 2026-09-30)
The owner tried a second board layout, "E", on the LP3 beside D's strip (N3, N4), and chose E (#17
was the trial). E changes only the board screens: a Puzzle, a Game against the computer, a
Correspondence Game and a replayed Game. N1, N2 and N5 to N10 hold as they are, and so do every
button's action, the second taps, the wheel's rules and the back stack. The invite page and the
list pages keep their top bars and strips. E1 to E4 SUPERSEDE N3's back arrow in the strip and N4's
placement of the mark in the strip, on every board; N4's mark itself (its squares, sizes, label and
ink end) holds, now in the top bar. The strip's back arrow and its captured-pieces band (P3's
place) are gone from the code; the strip is the invite page's and the Play a friend list's.
- E1 The top bar. SUPERSEDES N3's arrow in the strip and N4's mark in the strip, on the boards.
  RULING: every board opens with LightOS's top bar, light-sdk's own `LightTopBar`: 3 grid units tall (40 dp on the
  LP3), with `LightIcons.BACK` at its standard left place, as on every other page. The arrow's label
  is "Back" and it does what system back does (`goBack`). The status is the bar's title, in the
  SDK's title style (`Fine`, centred, at most `CENTER_MAX_WIDTH_UNITS`, 18 grid units, 240 dp). The
  right of the bar holds N4's three squares, as the SDK's right button, wherever D's strip had the
  mark: the computer's board and a Correspondence Game's board, but not in Review, not on the
  new-game status, not on the Puzzle board (N5) and not on a replay. The squares keep N4's sizes
  (8 by 7 px, 28 px apart) and its label ("Open the Menu"), and their ink still ends 16 dp from the
  screen's edge. They are centred on the bar's middle line, as the SDK centres the arrow, because
  the bar has no text line beside them. The target is the SDK's button box: the bar's height, 40 dp
  square, ending one grid unit in.
- E2 The board and the action row. SUPERSEDES N3's strip under the board (and R1.8's board 12 dp
  below the top of the app area). RULING: the 312 dp board sits directly under the bar (40 + 312 = 352 of the LP3's 389 dp). The rest, about 37 dp, is the
  action row: this moment's buttons, the same as D's (`PuzzleStrip`, `GameStrip`, `FriendStrip`, at
  most three, contradiction 2), at its right in LightOS `Copy`. Each button's target is the row's
  full height, and the last label's ink ends 16 dp from the edge, under the mark. (AMENDED by E8
  and E9: the buttons are centred as a group, on their own line under the Captured Pieces on a
  Game's board, each target that line's height; on the Puzzle board, in the row's middle.) The row has no
  back arrow, no Menu mark and no status. When every Puzzle is finished, the page is the top bar
  alone, saying so.
- E3 A long status. RULING: the title may take a second line at the same `Fine` size, since two lines
  are 37 dp, inside the bar's 40 dp. Measured in `StripFitTest`'s Akkurat stand-in, every status a
  board can show fits one line today; the widest, "Tap a piece, then a square", is 234 of 240 dp,
  and "Draw by the 50-move rule" and "You won by resignation" also take one line. The second line
  is a margin for Akkurat and for new copy. Rejected: a smaller size (the SDK's two-line `Detail`
  title, 13 dp), which every status would pay for although none needs it; and moving long Results
  into the action row, where they would compete with Next, Rematch and the Captured Pieces. R4.16's
  line counts were D's strip's and don't apply to the title (`GameStrip.statusLines` and
  `FriendStrip.statusLines` still carry them, for the invite page's strip and as a copy budget). `StripFitTest` checks every Puzzle,
  computer, Correspondence Game and replay state against the SDK's constants, read from its source.
- E4 The Captured Pieces. SUPERSEDES P3's place (the strip's top band) and its strip rules (the
  lowered text, the closer lines, the 4 dp button padding). RULING: on a Game's board the row
  shares the action row, at its left. Its room runs from the board's left edge to 4 dp
  before the first button, and never past the board's right edge. (AMENDED by E6: before the
  board's widest button set, not the one shown. The place SUPERSEDED by E7: the row is the action
  row's top line, across the board's width, and the buttons have a line of their own.) The bottom Side's end grows from
  the board's left edge, and the other Side's grows inwards from the room's right end, beside the
  buttons. Everything else in P3 holds: the order, the drawings, the Material Lead, every piece
  drawn, and the row never wraps. When the two ends would come closer than 4 dp, every step shrinks
  by one factor until they don't, as a fanned hand closes (`CapturedRowLayout.fit`). In the
  stand-in, eight pieces taken by each Side keep P3's own steps beside any one computer button, and
  so do fifteen a Side at the board's full width. Fifteen a Side close to 0.94 of the steps beside
  Hint and 0.70 beside Move now. Beside Accept and Decline, eight a Side close to 0.77 and fifteen a
  Side to 0.45. Rejected: showing the row only when no button is shown, because a computer board
  nearly always has one, so P3 would vanish there. E has no reserved band: the pieces and the
  buttons share one line.
- E5 Tests. (The action row's tests AMENDED by E10.) `StripFitTest`: `theTopBarIsLightOsTopBar` (the bar's height, padding, title width and
  `Fine` read from light-sdk's source), `everyBoardStatusFitsTheTopBarTitle`,
  `everyActionRowFitsUnderTheBoard`, `theCapturedPiecesShareTheActionRow`, and N9's offers in the
  top bar (`theOffersHoldOneLineWithTheMenu`). The strip tests now measure only the invite page's and
  the Play a friend list's strips (`everyStatusFitsNextToItsButtons`) and N4's mark
  (`theMarkSitsWhereLightOsDrawsIt`). D's tests for the arrow's place in the strip and
  `CapturedRowTest`'s tests of the strip's band are gone with the code they measured.
- E6 The Captured Pieces stay still as the buttons change (orchestrator, from #18's review;
  AMENDS E4's room). (SUPERSEDED by E7: the row no longer shares a line with the buttons, so it has
  no room beside them to keep; its aim, that only a capture or a Review step moves the row, holds.) Contradiction: E4 anchored the row's right end, and `fit`'s factor, to the room
  the buttons shown leave, so the top Side's pieces jumped sideways whenever the set changed (every
  Ply on the computer's board: Thinking · Move now, Your move · Hint, a Game Hint being found with no
  button, Next; about 120 dp when Send and Undo come and go), and in crowded rows the left end's
  spacing breathed too, against P3's "nothing moves". RULING: each board keeps one room for the
  Captured Pieces, from the board's left edge to 4 dp before the widest button set that board can
  show, at most the board's width; both ends and `fit`'s factor are computed in that room, so only a
  capture or a Review step changes the row. The buttons stay right-aligned. The sets are the strips'
  own lists (`GameStrip.BOARD_BUTTONS`, `GameStrip.REPLAY_BUTTONS`, `FriendStrip.BOARD_BUTTONS`),
  measured in `Copy` at run time (`BarLayout.capturedRoom`); the set shown is counted with them, so
  a set missing from a list can never overlap the row. In the stand-in: the computer's board keeps
  the room beside Move now, 207 dp (eight pieces a Side keep P3's steps, fifteen close to 0.70); a
  replay beside Latest, 245.5 dp (1 and 0.86); a Correspondence Game's board beside Accept and
  Decline, 148 dp, invite and Claim states included (0.77 and 0.45). The Puzzle board has no Captured
  Pieces (P3), so it keeps no room and its buttons sit as before. Rejected: a room per button set (E4
  as it was: the jump), and a fixed width for every board (the replay and the computer's board would
  pay for Accept and Decline, which they never show). Test: `StripFitTest.theCapturedPiecesStayStillAsTheButtonsChange`
  (each board's list holds every set its strips show; the room and the placed row are identical
  beside every set; the room clears every set; fifteen a Side still fit with the ends apart).

- (Layout E's E7 to E10 below are this section's own, as its E1 to E6 are: Round 2B's E7, the cap of
  five Games, and E8, the rematch, keep their ids, and a citation from v3's rulings means those.)
- E7 The Captured Pieces from each edge, high and tight (owner, 2026-09-30, from mocks). SUPERSEDES
  E6 and E4's place (the action row's left, beside the buttons); AMENDS P3's place, returning its 3
  dp. RULING: on a Game's board the Captured Pieces are the action row's top line: their drawings 3
  dp under the board's bottom edge (P3's original placement), across the board's full 312 dp. The
  bottom Side's end files in from the board's left edge, the top Side's from its right edge, and the
  Material Lead sits after the leading Side's end, as P3 has it. The two ends tighten by `fit`'s one
  factor only when they would meet across the full width: fifteen a Side with a lead of 103 either
  way keep P3's steps there, with every piece drawn and the ends apart, so on the LP3 `fit` never
  acts; it stays for the extreme cases (a narrower board). The row no longer depends on the buttons,
  so it stays still by construction as they come and go: E6's room (`BarLayout.capturedRoom`,
  `CAPTURED_GAP`), `ActionRow`'s `buttonSets` and its `require`, and the button-set lists
  (`GameStrip.BOARD_BUTTONS`, `GameStrip.REPLAY_BUTTONS`, `FriendStrip.BOARD_BUTTONS`) and their
  guard test (`everyBoardButtonIsInItsBoardsButtonSets`) are deleted: nothing else read them, and
  `StripFitTest` still measures every set each board shows from the strips themselves. Also gone:
  `BarLayout.BUTTONS_END` (E2's 16 dp end, E8). `ActionRow` places the row with
  `CapturedRow(captured, POSITION_VIEW_SIZE, ...)`.
- E8 The buttons' line and the vertical budget. AMENDS E2 (the buttons at the right, the last
  label's ink 16 dp from the edge, each target the row's height) and P3's sizes. RULING: on a Game's
  board the buttons have their own line at the bottom of the action row, under the Captured Pieces'
  line, CENTRED as a group horizontally on the screen (which centres them on the board), each a
  target the full height of its line, with their labels, semantics labels and the Button role as
  before. The budget, on the LP3: the app area is 1168 px, so the row is 1168 - 120 - 936 = 112 px,
  37.33 dp. The Captured Pieces' line is 3 dp plus the drawings; the buttons' line is the rest. A
  label's ink in `Copy` (30 design px, 19.45 dp) runs from its tallest letter, 0.750 em above the
  baseline ("l", "d", "h"), to a descender, 0.214 em below ("p" in Accept, "y" in Retry): 0.964 em,
  18.75 dp, measured on the emulator's Roboto. At P3's 17 dp the lines would need 3 + 17 + 18.75 =
  38.75 dp, and at 16 dp 37.75: neither fits. The drawings are therefore 15 dp (45 px), the largest
  whole size that fits: the pieces' line is 18 dp, the buttons' line 19.33 dp, and the labels' ink is
  centred in it (`BarLayout.labelBaseline`, placed by the text's first baseline), about 0.3 dp clear
  of the drawings' box above and of the app area's bottom edge below, so nothing overlaps and nothing
  is clipped. The drawings' ink ends 2 dp above their box (every piece stops at 38.7 of 45 units), so
  the visible gap under the pieces is about 2.4 dp. The steps keep the owner's mock-up's proportions
  (17 and 50 px beside its 52 px drawing), rounded to half a dp at 15 dp: 5 dp within a kind, 14.5
  dp to the next (P3's 5.5 and 16.5 at 17). The buttons' line starts under the pieces' line on every
  Game's board, before the first capture too, so the labels never move. Akkurat's ink on the LP3 is
  unmeasured (the emulator has Roboto): a label taller than 0.994 em would touch the drawings' box;
  check it on the LP3 with a descender label (Accept, Retry) beside crowded pieces.
- E9 The Puzzle board's buttons (orchestrator's choice). RULING: the Puzzle board has no Captured
  Pieces (P3), so its buttons' line is the whole action row: Hint, Solution, Next and Latest centred
  as a group horizontally, like a Game's board, and their ink centred vertically in the row, each
  target the row's height. Rejected: the bottom line, as on a Game's board, which would leave 18 dp
  of empty row between the board and the labels for pieces that never come; the labels would read
  as fallen away from the board. The horizontal centring is what the boards share.
- E10 Tests. `StripFitTest`: `everyActionRowFitsUnderTheBoard` (every board's buttons, centred, stay
  within the board's edges with equal margins), `thePiecesAndTheButtonsEachHaveTheirLine` (the row is
  37.33 dp; the pieces' line is 3 + 15 dp; the labels' ink lies inside the buttons' line, clear of
  the drawings and the app area's bottom, centred; a 16 dp drawing would not fit; the Puzzle board's
  ink is centred in the whole row), `theCapturedPiecesRunFromEachEdgeOfTheBoard` (fifteen a Side,
  lead -103, 0 and 103, both Sides at the bottom: P3's steps, every piece inside the board, the
  bottom Side's end at the left edge, the top Side's at the right, the lead after the leading end),
  `theCapturedPiecesDontDependOnTheButtons` (`ActionRow` places the row at the board's width and the
  buttons' line by the board alone, and no room is left; each Game board shows several sets). They
  replace `theCapturedPiecesShareTheActionRow`, `theCapturedPiecesStayStillAsTheButtonsChange` and
  `everyBoardButtonIsInItsBoardsButtonSets`. `CapturedRowTest.theDrawingsKeepTheOwnersMockUpsProportions`
  replaces `theDrawingsMatchTheOwnersMockUp`. Accessibility is unchanged: the row's label
  (`CapturedRowTest.theRowsLabelReadsBothEndsAndTheLead`) and each button's label and role are as
  before, the row read before the buttons.

## Puzzles page and Pieces (owner, 2026-09-30)
After an adversarial review of Navigation D. Home had eight rows and scrolled on the LP3, three of
them the Puzzles' own (the rating, Missed and the Puzzle board), and Pieces sat among places although
it is about how a board is drawn. The Puzzle board had no Menu, so the Puzzle's id moved to About,
far from the Puzzle it names. The rulings: Puzzles becomes a place of its own, a list page, with the
Puzzle board a step above it; Pieces moves into the Menu of every board, where its change shows.
- N11 Home, five rows. AMENDS N1 and, through it, N10. RULING: Home's rows, in order: "Puzzles ·
  1176?" (the Player Rating as shown today, "?" and all; "Puzzles" alone until `puzzles.json` is
  read), "Play the computer", "Play a friend" (with "· Your move: N" while that count is above 0, W6;
  absent while the Relay URL is empty, W8), "Games", "About". Player Rating, Missed and Pieces leave
  Home. The rating is on the Puzzles row, the one place it belongs to, and still shows once on Home.
  With five rows Home fits one screen, so by N10's measured rule the wheel stays with LightOS there
  (Home still measures it: were a row ever to overflow, it would take the wheel again). `HomeRows`
  and `HomeTest`.
- N12 The Puzzles page. NEW; AMENDS N1's "Puzzles opens the Puzzle board". RULING: Home's Puzzles row
  opens a list page with LightOS's top bar (the back arrow to Home, the title "Puzzles"), not the
  board. Its rows, in order:
  1. A first row that says what it will do, from the Puzzle flow's state, and never names a Puzzle:
     an Attempt under way on the rated Puzzle, Try Mode included, "Continue Puzzle" (opens the
     board); at a Result, "Next Puzzle" (advances to the next Puzzle, as the strip's Next does, then
     opens the board on it); during a Missed replay, "Back to the rated Puzzle" (ends the replay,
     whatever its stage, and opens the board on the rated Puzzle; the rated one's own state is
     untouched, since `attempt = replay ?: current` and only `replay` is cleared); before the seed
     screen is answered, or after Reset rating, "Start" (opens the board, which shows the seed
     screen as today, D4, F6); with the Pack used up, a plain line, not a button, "Every Puzzle is
     finished" (`UiCopy.PACK_FINISHED`, the strip's words). Before `puzzles.json` is read, "Continue
     Puzzle", as the board it opens waits for the file.
  2. "Missed · N" (N13).
  3. "Past Puzzles" (N14).
  4. "Player Rating · 1176?" (N15).
  `PuzzlesRows` holds the rows as data, `PuzzlesPageTest` the first row for every state and what
  each tap does.
- N13 Missed, from the Puzzles page. KEEPS D2 and F4; FIXES a silent no-op. RULING: "Missed · N"
  opens today's Missed page: its rows replay unrated (D2), and a clean replay leaves Missed (F4).
  The name stays Missed, the glossary's term. A row whose Puzzle the Pack no longer has is shown
  lightened and does nothing. F1's carry-over already drops such rows when a new Pack is noticed; the
  page checks again, on the background read it already makes (`prefetchMissed`), for a row the
  carry-over can't see. Before, `replayMissed` returned the state unchanged for such a row and the
  page still went back with a result, so the board opened on the rated Puzzle instead of the one
  tapped. Now a replay that doesn't start marks its row and stays on the page.
- N14 Past Puzzles. NEW; MOVES F11's rating-history rows off the Player Rating page. RULING: "Past
  Puzzles" opens a read-only list of the rated Attempts, newest first, in today's words ("1523 ·
  Solved +12", "1541 · Failed −9", "1500 · Solved, unrated" for a Hinted one): not tappable, since a
  Puzzle once finished is never served again (A7) and Missed is how one is played again. Empty:
  "No rated Puzzles yet". `PuzzleData.HISTORY_CAP` rises from 50 to 100, the same as Missed's.
  `decode` is lenient, so a file of 50 still reads, and grows to 100 as Attempts are scored; an older
  build that reads a file of 100 shows it whole and trims it to its 50 on its next result. The file
  stays small: a history row is about 90 bytes of JSON, so 100 rows add some 4.5 KB to a file that
  also holds 100 Missed rows and up to a few thousand finished ids. "Past Puzzles" is UI vocabulary
  (docs/domain-ignore.txt), the page's name, not a new domain concept: the rows are Attempts, and
  the list is the history `PuzzleData` already keeps. CONTEXT.md's _Avoid_ for Review ("history
  mode") is about stepping through Moves and doesn't collide.
- N15 The Player Rating page. AMENDS N1's page and F11's "Rating screen". KEEPS F5. RULING: the page
  shows the rating ("1176?"), while it is provisional a static line, "The ? goes after about 50
  rated Puzzles." (a countdown would lie: Glicko's deviation can rise between Puzzles, DESIGN.md "The
  puzzle flow" has the measured 48), and Reset rating with its second tap, "Tap again to reset" (F5).
  Reset replaces the page with the Puzzle board, which asks the seed question again (N16). The
  history rows are Past Puzzles' now (N14).
- N16 Depth and launch. AMENDS N2. KEEPS A10's intent. RULING: the Puzzles page is a place, one
  step from Home; the Puzzle board is a step above it: back from the board goes to the Puzzles page,
  then to Home. A cold start in Puzzles pushes Home, then the Puzzles page, then the board, so it
  still opens straight onto the board (A10) and back walks down the levels. The Relay-less friend
  mode opens the same stack (Y6). A Missed replay replaces the Missed page with the board, Reset
  rating the Player Rating page, and the first row's tap pushes the board over the Puzzles page, so
  the stack is never deeper than root, place, page, detail: Home, the Puzzles page, Missed / Past
  Puzzles / Player Rating or the board, and nothing above the board but its Menu. The depth rule for
  every place: Home; a place (the Puzzles page, the computer's board, Play a friend, Games, About);
  a page or board over it; and a detail over that (a board's Menu and its pages, a replayed Game).
  The Puzzles page writes `mode.txt` as Puzzles when Home opens it, as the board did; its pages
  don't. `Navigation`/`HomeNavigator` (`HomeTest`).
- N17 Pieces in every board's Menu, and every board has one. AMENDS N4 and N5; SUPERSEDES N5's "The
  Puzzle board has no Menu" and its "never settings", and N1's and P2's placement of the Pieces row.
  RULING: N5's rule becomes "a Menu holds everything about this board: its actions and how it is
  drawn". Every board's strip has the Menu mark on the right, as N4 draws it, in every state but
  Review on the board of a Game in play (the computer's and a Correspondence Game's), whose Menu
  holds that Game's actions (N21). Under Layout E the mark is the board's top-bar right button (E1), and this SUPERSEDES
  E1's "not on the Puzzle board (N5) and not on a replay"; the fit is E's title and action-row
  measure (E3, E5), which the mark doesn't change. The Menus:
  - The Puzzle board: the mark in every state, Review included (`StripFitTest` measures each). The
    end of the Pack draws no board and has no mark. Its Menu: "Pieces · Geometric", then
    the Puzzle on screen as two grey lines (N18).
  - The computer's Game and a Correspondence Game: their actions as N5 lists them, then Pieces last,
    set apart by a gap as wide as the Menu's section spacing, so a fast wheel over-scroll to the end
    lands on Pieces and never on an action.
  - A replayed Game from Games: the mark too (it had none), in Review as well (N21); its Menu is
    Pieces alone. Flip board and Moves for a replay are out of scope.
  - The invite page and the list pages (Play a friend, Games, the Puzzles page) draw no board and have
    no Pieces row; a deleted Game's Menu, opened from the Play a friend list, has none either.
  A tap on Pieces still moves to the next set and stays on the Menu (P2), and the row waits for the
  Piece Set like before (M4). `PuzzleMenu`, `GameMenu`, `FriendMenu`, `GameReviewMenu` (`MenuTest`).
- N18 The Puzzle's id, in its board's Menu. AMENDS N8 (About no longer shows it) and returns S1's
  placement. RULING: the Puzzle board's Menu ends with "Puzzle 00sHx" over
  "lichess.org/training/00sHx", in grey, as text (D5, D7), for the Puzzle on screen: a Missed replay's
  Menu names the replay's Puzzle, never the rated one below it. About ends with the Tool's notices.
- N19 The Pieces row shows the set. NEW. RULING: the row draws the chosen set's white king, queen and
  knight beside its label, at the row text's height, from the same `PieceVectors` the board uses, so a
  tap shows the change without leaving the Menu. The row stays one line at the LP3's 360 dp with the
  widest set's name (`MenuTest`), and its semantics label is its text.

## The wheel on a board (owner, 2026-09-30)
Seen on the LP3: scrubbing the Moves with the wheel also changed LightOS's brightness. A board took a
turn only when it changed the Position shown: `Review.wheel` returned null for a clockwise turn at the
latest Position and a counter-clockwise one with no Moves, so the board's view model returned false
and LightActivity handed the turn to LightOS. A fast scrub back to the present overshoots the end, and
every detent past it changed the brightness.
- N20 A board takes every turn. AMENDS R1.9's "otherwise return false" for boards; KEEPS F2, F3,
  R4.17 and `TakenKeys` as they are. RULING: a board (a Puzzle, a Game against the computer, a
  Correspondence Game, a replayed Game) takes every counter-clockwise and clockwise turn, even one that
  moves nothing: at the latest Position, at Ply 0, and before any Move (the Puzzle's held start
  included), a turn is taken and changes nothing. The click keeps F2: in Review it returns to the
  latest Position; on the latest Position it goes to LightOS, for the flashlight. Pages that are not
  boards keep F3: one that scrolls takes the wheel, one that fits leaves it with LightOS (Home, a
  Menu that fits, the lists), so brightness still works there. The seed screen and the end of the
  Pack draw no board and leave the wheel with LightOS. `Review.wheel` holds the rule (`ReviewTest`).

## Puzzles page review follow-ups (orchestrator, 2026-09-30)
From the review of the Puzzles page PR (#20).
- N21 Which Menus hide in Review. AMENDS N17. Contradiction: N17 hid the mark in Review on every
  Game's board, the replay's included, for "R4.16's room", a reason from D's strip that E3 retired
  (the status is the top bar's title now, and the mark doesn't change its fit). RULING: a Menu that
  holds a Game's actions hides in Review, since Takeback, Resign, Offer draw and the rest act on the
  live Game, not on the Position shown: the computer's board and a Correspondence Game's board. A
  replayed Game's Menu holds only Pieces, which acts on no Game, so its mark stays in Review, as the
  Puzzle board's does. `GameStrip.replay`, `MenuTest`.
- N22 The first row's edge cases. AMENDS N12. (a) A Missed replay started after Reset rating, before
  the seed is answered: the board shows the replay the tap asked for (a replay is unrated and needs no
  rating), and the seed screen follows when it ends (Next, or the first row). The first row reads
  "Start" while the seed is unanswered, replay or not, and its tap ends the replay, so the board it
  opens asks the seed; before, the board showed the seed screen and the tapped replay only after it,
  under a "Start" that didn't say so. `PuzzleState.seedScreen`. (b) With the Pack used up, the first
  row is the plain "Every Puzzle is finished" even over a Missed replay: "Back to the rated Puzzle"
  would open the finished line, and there is no rated Puzzle to go back to. The replay, never saved,
  is left as it is; a Missed tap replaces it. `PuzzlesRows.start`, `PuzzlesPageTest`.
- N23 The Missed check. AMENDS N13. RULING: the check reads each Band file at most once, for every
  Missed id at once (`Pack.prefetch`: the Bands the ratings name first, then, for ids still missing,
  the others), where it read every uncached Band once per lost id on each open. An id found gone is
  never looked for again in the process (the Pack is the Tool's own assets), and a Puzzle the last
  check found is kept without a read; each Band's Puzzles are found as soon as that Band is read. A
  tap never reads a file on the main thread. A lost row's tap starts nothing and the page stays. A
  tap on a row the check hasn't read yet starts nothing at once either, and is kept (the latest tap
  only): when the check ends, that Puzzle replays and the page goes to the board, as a tap on a read
  row does; if the check finds it gone, nothing starts and the row lightens; if the page was left
  first (back, or hidden), the kept tap is dropped and replays nothing. The check runs when the
  Puzzles page or Missed opens; one opened while a check on an older Missed list still runs (the
  list changed on the board meanwhile) makes it run again on the new list, and the kept tap lands
  after that run. "Missed · N" counts only the rows that replay. `PuzzleOwner.replayMissed`,
  `prefetchMissed`. Puzzle stays capitalised as the glossary term in the page's copy:
  "Continue Puzzle", "Next Puzzle", "Back to the rated Puzzle", "Past Puzzles", like "Every Puzzle is
  finished" and the strip's "Next Puzzle". `PuzzlesPageTest`, `PuzzleOwnerMissedTest`.
