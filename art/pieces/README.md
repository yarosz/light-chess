# Chess pieces

The twelve SVGs here are Chess's own piece set, original drawings made for this project (decision log
P1). `<side><piece>.svg`: side `w` or `b`, piece `K Q R B N P`, each on a 45 × 45 viewBox.

## Licence

CC0 1.0 Universal (`LICENSE.txt`): no rights reserved. `NOTICE` and the About screen say so in one line.

## The design rules (P1)

- LightOS glyph style: geometric, flat, no shading or texture.
- One silhouette per piece. A black piece is that silhouette, solid black, with thin white cuts where
  it needs them (the bishop's slot, the knight's eye).
- A white piece is the same silhouette filled white over a wider black stroke drawn first, which gives
  it an outer line on either square, with thin black cuts.
- Keep the drawings as they are: a change to a piece is a design change for the owner to approve.

## How they are used

`scripts/build-pieces.py` turns them into Kotlin ImageVector code,
`tool/src/main/kotlin/com/yarosz/chess/board/PieceVectors.kt`, which is committed: Light's builder runs
offline and the Tool parses no SVG at run time. The paths keep the file's order, so the outline is
painted under the white body. After changing a file here, rerun the script;
`scripts/build-pieces.py --check` fails when the committed Kotlin is stale, and CI runs it.
