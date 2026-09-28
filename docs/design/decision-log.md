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
