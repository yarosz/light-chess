# Chess pieces

Chess's own two piece sets, original drawings made for this project (decision log P1, P2). The player
picks one in the Menu, the Piece Set; it changes how the pieces look and nothing else.

- `geometric/`: the geometric set, built from straight facets. The default.
- `rounded/`: the rounded set, built from circles, capsules and rounded rectangles, with a shared
  rounded foot under every piece but the pawn. `rounded/tools/shapes.py` holds its geometry and
  `rounded/tools/gen.py` writes its SVGs (`python3 art/pieces/rounded/tools/gen.py` rewrites them in
  place).

Each set is twelve SVGs named `<side><piece>.svg`: side `w` or `b`, piece `K Q R B N P`.

## Licence

CC0 1.0 Universal (`LICENSE.txt`, for both sets): no rights reserved. `NOTICE`, the README and the
About screen say so in one line.

## The design rules (P1, for both sets)

- LightOS glyph style: flat, no shading or texture, on a 45 × 45 viewBox.
- One silhouette per piece. A black piece is that silhouette, solid black, with thin white cuts where
  it needs them (the bishop's slot, the knight's eye).
- A white piece is the same silhouette filled white over a wider black stroke drawn first, which gives
  it an outer line on either square, with thin black cuts.
- Keep the drawings as they are: a change to a piece is a design change for the owner to approve.

## How they are used

`scripts/build-pieces.py` turns both sets into Kotlin ImageVector code,
`tool/src/main/kotlin/com/yarosz/chess/board/PieceVectors.kt`, which is committed: Light's builder runs
offline and the Tool parses no SVG at run time. The paths keep the file's order, so the outline is
painted under the white body. After changing a file here, rerun the script.
`scripts/build-pieces.py --check` fails when the committed Kotlin is stale, and CI runs it;
`scripts/build-pieces.py --self-test` checks the converter on small inputs.
