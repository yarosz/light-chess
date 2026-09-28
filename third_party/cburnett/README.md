# cburnett chess pieces

The twelve SVGs here are Colin M.L. Burnett's standard chess pieces ("cburnett"), unmodified, as
downloaded from Wikimedia Commons on 2026-09-28:

- Category: https://commons.wikimedia.org/wiki/Category:SVG_chess_pieces
- Files: `https://commons.wikimedia.org/wiki/File:Chess_<p><c>t45.svg`, where `<p>` is one of
  `k q r b n p` and `<c>` is `l` (White) or `d` (Black); for example
  https://commons.wikimedia.org/wiki/File:Chess_klt45.svg.

## Licence

On Commons each file is offered under a choice of the GFDL, a BSD licence and the GPL
(`{{self|GFDL|migration=relicense|BSD|GPL}}`, author Cburnett, 2006-12-27). This Tool takes them
under the **BSD 3-Clause** option (decision log R1.7), whose text is in `LICENSE` here. `NOTICE`
credits the author and the licence, and so will the About screen (A9, D7).

## How they are used

`scripts/build-pieces.py` turns them into Kotlin ImageVector code,
`tool/src/main/kotlin/com/yarosz/chess/board/CburnettPieces.kt`, which is committed: Light's builder
runs offline and the Tool parses no SVG at run time. After changing a file here, rerun the script.
`scripts/build-pieces.py --check` fails when the committed Kotlin is stale; CI runs it.
