# Chess

Chess Puzzles for the Light Phone III.

Solve real positions from the Lichess puzzle database, 46,971 of them, chosen for your level.
Your Player Rating moves with every Puzzle, and the ones you missed wait for another try.
Works offline, with no account and nothing tracked.

> **Status: 0.1.0, feature-complete for v1, not yet listed by Light.** A Light Phone III Tool built on
> [Light's SDK](https://github.com/lightphone/light-sdk). Playing the computer comes in v2, and a friend
> by correspondence in v3.

<!-- Screenshots: LP3 photos go in docs/screenshots/ (first Puzzle, a result, the Menu, About). -->
_Screenshots from a Light Phone III are coming._

## What it does

- **Puzzles.** Tap a piece, then a square, or drag it. A wrong Move is taken back and you can try again.
  "Hint" marks the piece to move; "Solution" plays the line for you.
- **Player Rating.** A Glicko-2 rating, like Lichess's own, picks each Puzzle near your level. Answer
  one question on first launch, or skip it.
- **Missed.** The last 100 Puzzles you failed or needed a Hint for, to replay unrated.
- **Review.** Turn the scroll wheel back to step through the Moves of the Puzzle on screen.
- The screen stays on while you think, for up to five minutes without a touch.

## Install

- **From Light's Tool directory**, once Chess is listed there. This is the way for everyone.
- **For tinkerers, a dev-signed build over adb.** Your Light Phone III needs developer mode and
  Allowed tools set to "All tools". Build and install it yourself:

  ```sh
  git clone --recursive https://github.com/yarosz/light-chess.git
  cd light-chess
  ./gradlew :tool:assembleDebug
  adb install tool/build/outputs/apk/debug/tool-debug.apk
  ```

  A dev-signed build is signed with the SDK's public development key, not Light's. To move to the
  Light-signed build later, uninstall the dev-signed one first; that deletes your rating and history.

## Build

You need JDK 17 and the Android SDK (API 36). With [mise](https://mise.jdx.dev), `mise install`
provides both, and `mise tasks` lists the emulator and install commands.

```sh
./gradlew :tool:testDebugUnitTest :tool:assembleDebug
```

A plain `assembleDebug` builds for LightOS on a Light Phone III. For the emulator, use `mise run tool`
(it builds, installs and launches) or `scripts/emulator-build.sh ./gradlew :tool:assembleDebug`.
`AGENTS.md` describes the layout and how changes land; `RELEASING.md` how a release is made.

## Privacy

Chess never uses the network. Nothing leaves this phone. The Puzzles ship inside the Tool, and your
rating and history stay in the Tool's own storage. Chess declares no permissions of its own; the
Android permissions its package lists come from Light's SDK. See `SECURITY.md`.

## Credits

- Puzzles: the [Lichess puzzle database](https://database.lichess.org/#puzzles) (lichess.org),
  released under CC0. The Pack is built from the dump of 2026-09-09 (`docs/pack.md`).
- Pieces: the cburnett set by Colin M.L. Burnett, under the BSD 3-Clause licence
  (`third_party/cburnett/`).
- Built on Light's SDK (MIT) and the libraries it brings, listed in `NOTICE` and on the About screen.

## Licence

Copyright 2026 Nicolas Yarosz. Chess is free software under the GNU General Public License, version 3
or later (`LICENSE`). Contributions need a copyright assignment (`CONTRIBUTING.md`).
