# v2 PR 4: the game screen (play the computer)

Base: feat/v2-play (v1 0.1.0 + engine + bench + Levels + game record + Book). Everything it needs
exists as tested pure Kotlin; this PR wires it into a screen and the Menu.

Read: LEDGER.md, AGENTS.md, DESIGN.md, CONTEXT.md, docs/levels.md, docs/book.md, and in
docs/design/decision-log.md: B2, B3, B5, B6, B7, D6, D10 (a board flip in v2), E2, F7, F8, F11,
G1, contradictions 2/3/4/6, "Takeback rule corrected", "v2 PR 3", "v2 PR 5 core", "v2 PR 6". Also
PLATFORM.md lifecycle (onAppPause, one process-wide owner, relaunch) and the keys section.

Scope
1. Menu: "Play the computer" (D6). The Tool opens on the mode LAST USED.
2. New-game screen: Level 1-8, Colour (White / Black / Random), and Think Time (3/10/30 s, shown
   only at Level 8). Remember the last choices. One Game in progress at a time (B5); starting a new
   one while one is in progress needs "Tap again to replace" (F7), and the old one is saved as
   unfinished (*).
3. The game screen (reuse PositionView, MoveInput, Strip, Review, Wheel):
   - The user's colour at the bottom; a board flip in the Menu (D10 v2).
   - The computer moves via EngineHost.play(Level, ThinkTime, seed) and BookPolicy.pick first (Book
     rulings: L1 none, ply limits, leaving the Book is final). The Game seed is stored with the
     GameRecord, so a resume or Takeback replays the same picks. The engine move animates in 200 ms
     (F11); user moves are instant.
   - While the computer thinks, the board is locked and the strip stays live (contradiction 3).
   - Strip buttons by context (contradiction 2): on your turn Takeback / Game Hint / Menu; while
     thinking Move now / Menu. Resign (with a second-tap confirm) and Offer draw live in the Menu.
   - Game Hint = the Level-8 best Move shown briefly (a ring on the piece plus the target), counted
     in the record (B5); it stays the engine's Move even in the Book.
   - Takeback per "Takeback rule corrected" (stop any search, cut to before the user's latest Move),
     counted.
   - Draw offers: the engine accepts per G1 (eval <= -50 cp past move 30, OR the dead-level rule),
     judged on the true eval; it answers at once ("Draw declined" / "Draw agreed"); a re-offer is
     allowed only after 10 more moves. The engine never offers a draw or resigns (F8). Automatic
     draws (threefold, 50-move, insufficient material, stalemate) come from the rules core.
   - The Result shows in the strip with Next / New game; the finished Game goes to history (last 50).
   - Keep the screen on while the engine thinks, and while it's the user's turn with the last input
     < 5 min ago (contradiction 4, B6).
   - onAppPause: stop the search, save the Game (PGN + FEN checkpoint); on resume, re-run the
     computer's turn if it was thinking (B6), from the saved seed.
   - Wheel: Review through the Game's Moves (R1.9); click returns to the latest Position (F2).
4. Menu entries: Games (history list of the last 50: date, opponent Level, Result; tap to replay in
   Review with the wheel), Moves (SAN, two columns, wheel-scrollable; F11), Think Time at Level 8.
5. About gains the engine credit (Pirarucu by Raoni Campos (ratosh), GPL-3.0; D7) and the Book credit
   (already in UiCopy on feat/v2-book; check it's shown).
6. The strip copy must pass StripFitTest (the LightOS-font stand-in); add every new strip state to the
   test table.
7. v2.x "Play from here" (F7) from a Puzzle's start: include it only if it's small; otherwise leave a
   LEDGER item.

Verification
- Unit tests for the game-screen state machine (pure Kotlin): the turn flow, locking, Move now,
  Takeback in both states, Game Hint counting, draw offers (accept/decline/re-offer gate),
  resign-confirm, automatic draws, Results, resume mid-think, seed replay, the Book to engine
  handover, one-game-at-a-time replace.
- Emulator emulator-5556 only (AVD LightPhone3-chess, flags per scripts/emulator/RECIPE.md; dismiss
  ImmersiveModeConfirmation; check mCurrentFocus before input; shut it down after). Play a short
  Game at Level 1 as White to a Result, a Level 8 Game with Move now and a Takeback, a draw offer,
  resign, force-stop mid-think and relaunch (resumes and the computer moves), Menu → Games / Moves /
  About. Screenshots to a scratch folder outside the repo.
- `mise run light-build` on the committed HEAD (in the background); domain-drift clean;
  `scripts/ci.sh --dry-run`.
