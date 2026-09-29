# Levels

How the computer's eight Levels are built and how their settings were chosen (decisions B2, B3, E2,
E3; v2 PR 3). The settings live in `tool/src/main/kotlin/com/yarosz/chess/engine/Level.kt`.

## How a Level plays

- Each Level has a node budget per search. Nodes, not time, so a Level plays the same Moves on every
  phone and on the Mac; only the time differs (B2).
- Below Level 8, the computer searches the Position normally, then searches it again with the best
  Move so far excluded, up to N searches in all (E3). The Moves that score within the Level's margin
  of the best are the candidates, and one is picked at random. A search that falls outside the
  margin ends the sampling.
- The pick uses a random source seeded by the Game's seed and the Ply, so a Game replays exactly from
  its Moves on a fresh engine.
- Levels 1 and 2 also have a depth cap (2 and 4 plies), so a quiet Position with few Moves can't
  spend the budget looking deeper than a beginner would.
- Level 8 is one plain search: N = 1, no margin. Think time is a wall cap on a node budget of 1,000
  nodes per millisecond: 3 s (the default, 3M nodes), 10 s or 30 s (E2). The budget is about twice
  what the LP3 searches in that time, so on the phone the clock ends the search.
- Move now, at every Level, stops the search in progress, which keeps the Move of its last
  completed iteration. No further search starts, and the computer picks among the candidates found so
  far. At Level 8 that is the last completed iteration's Move.
- The first, unrestricted search is never altered. Its score (`LevelMove.trueScore`) is the true
  evaluation that draw offers are judged on (G1), whatever Move the pick plays.

## The table

| Level | Nodes per search | Depth cap | N | Margin (cp) | Nodes per Move (mean) | LP3 time per Move (est.) | Ladder gap (self-play) | Gauntlet gap | Avg cp loss | Blunders / 40 |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 60 | 2 | 4 | 100 | 276 | < 1 ms | | | 131 | 7.4 |
| 2 | 400 | 4 | 4 | 150 | 1,460 | 3 ms | +453 | +332 | 61 | 3.2 |
| 3 | 1,000 | - | 3 | 80 | 2,800 | 5 ms | +512 | +317 | 59 | 2.2 |
| 4 | 2,500 | - | 3 | 55 | 6,900 | 12 ms | +436 | +194 | 32 | 0.6 |
| 5 | 6,000 | - | 2 | 35 | 11,900 | 21 ms | +490 | +164 | 26 | 0.6 |
| 6 | 25,000 | - | 2 | 20 | 49,700 | 89 ms | +636 | +315 | 17 | 0.0 |
| 7 | 120,000 | - | 2 | 15 | 239,000 | 430 ms | +453 | +394 | 13 | 0.0 |
| 8 | 3M / 10M / 30M | - | 1 | 0 | wall-bound | 3 s / 10 s / 30 s | +470 | +306 | 11 | 0.0 |

- LP3 time = nodes per Move / 558,000 (the LP3's measured P50 nodes per second). Every Level below 8
  moves in well under a second; Level 7 is the slowest at about 0.43 s.
- Ladder gap: the Elo gap to the Level below from 80 self-play Games (20 openings × 2 colours × 2
  seeds). 95% intervals are about ±100 to ±250; results near 95% are near the limit 80 Games can
  measure.
- Gauntlet gap: the gap from games against Karballo, an outside engine (40 Games per pairing), fitted
  by maximum likelihood (`scripts/level-elo.py --only anchor`).
- Avg cp loss and Blunders / 40: the blunder profile below.

### No Elo labels

B2 allows "approx." Elo labels only once the Levels are anchored. The only anchor available without
installing anything is Karballo's built-in limiter, and it is too loose to label a Level with:

- Its 500 and 1000 settings measure about 505 Elo apart, which fits their labels. Its 1500 setting
  measures 755 above its 1000, and its full strength ("2100") is 680 above that.
- On the scale that fits 500 and 1000, the Levels come out near 835, 1165, 1480, 1675, 1840, 2155,
  2550 and 2855. Fitted to 1500 instead, every Level would be about 250 lower.
- The limiter's labels are its author's formula (a linear error rate from 500 to 2100), not
  measured against people.

So the UI names Levels by number only. A Stockfish `UCI_Elo` gauntlet (B8's cutechess run) can add
labels later, if Stockfish and cutechess-cli are ever installed.

### Self-play and the gauntlet disagree

Consecutive Levels measure 436-636 Elo apart against each other, but 164-394 against Karballo.
Self-play exaggerates gaps: the stronger configuration of the same engine punishes exactly the
errors the weaker one makes. The span from Level 1 to Level 8 is fixed by two requirements, a
beginner at Level 1 and full strength at Level 8. It is about 3,450 Elo in self-play and about 2,020
in the gauntlet, so seven even self-play gaps cannot all fall in 150-300. The settings aim for even
gaps. The gauntlet gaps, which are closer to what a person would feel, are 164-394.

## Blunder profile

Each Level played each of 40 middlegame Positions (moves 10-34, `tool/src/test/resources/engine/
middlegames.txt`) with 5 seeds. A judge scored the Position and the Position after the Level's Move
with a fixed-depth search (depth 12 in all, a fresh engine each time). The centipawn loss is the
difference, capped at 1,000. A blunder loses 200 cp or more, roughly a minor piece.

| Level | Avg cp loss | Blunders / 40 | Judge's Move |
|---|---|---|---|
| 1 | 131 | 7.4 | 13% |
| 2 | 61 | 3.2 | 18% |
| 3 | 59 | 2.2 | 25% |
| 4 | 32 | 0.6 | 28% |
| 5 | 26 | 0.6 | 35% |
| 6 | 17 | 0.0 | 37% |
| 7 | 13 | 0.0 | 43% |
| 8 | 11 | 0.0 | 45% |

- The curve falls at every Level. Level 8's 11 cp is the judge's own noise: Level 8 searches deeper
  than the judge.
- Level 1 drops 200 cp or more on about one of these Positions in five, as a beginner does, and loses about as much per
  Move as Karballo's "500" setting (166 cp and 8 blunders in the Karballo spike, by its own judge).
  It is not random: it scored 38.5/40 against a random mover (37 wins, 3 draws).

## Level 8 on the phone

The calibration plays Level 8 as the LP3 plays it at the default Think time: 1,674,000 nodes (3 s ×
558K), no clock. It is the unmodified engine. It beat Level 7 75/80 and full-strength Karballo at 2M
nodes per Move 31.5/40. On the LP3, the 3 s clock ends the search before the 3M budget.

## Method

All of this runs on the Mac's JVM with no phone and no installed tools (no Stockfish, cutechess-cli
or python-chess were available):

- `LevelCalibrationTest` (skipped unless `-Dcalibrate=<modes>` is set) plays the matches with our rules
  core as the referee. A Game ends by mate, stalemate, repetition, the 50-move rule or insufficient
  material. It also ends as a draw at 300 plies, or by adjudication when both players' own searches
  have agreed on a 1,000 cp edge for 10 plies. Each Game uses fresh engines, so Games are independent
  and run in parallel.
- Modes: `ladder` (each Level against the next), `blunders`, `random` (Level 1 against a random
  mover), `anchor` (against Karballo). `calibrate.set=3=1000/3/80;...` tries other settings without
  editing `Level`.
- Karballo runs in its own process: `spikes/karballo/flat/serve`, a line server over the spike's
  flattened Karballo (MIT, commit a709a7e), 300K nodes per Move unless `calibrate.karballo.nodes`
  says otherwise. Karballo stays out of the Tool's build.
- Tuning: first a node-only chain (N = 1) and a margin-only chain at a fixed budget, to see what each
  lever is worth. Then candidate ladders adjusted pair by pair toward even gaps, rerun with 80 Games
  per pair.

To rerun (about 40 minutes on an M3 Pro with 10 threads):

```sh
./gradlew :tool:testDebugUnitTest --tests com.yarosz.chess.engine.LevelCalibrationTest --rerun \
  -Dcalibrate=blunders,random,ladder -Dcalibrate.rounds=2 -Dcalibrate.threads=10
# Karballo anchor: flatten the spike (spikes/karballo/README.md), then
#   gradle -p spikes/karballo/flat :serve:installDist
./gradlew :tool:testDebugUnitTest --tests com.yarosz.chess.engine.LevelCalibrationTest --rerun \
  -Dcalibrate=anchor -Dcalibrate.karballo=spikes/karballo/flat/serve/build/install/serve/bin/serve \
  -Dcalibrate.elos=500,1000 -Dcalibrate.levels=1-3
python3 scripts/level-elo.py --only anchor    # or --only ladder
```

The anchor runs used: Karballo 500 and 1000 against Levels 1-3; 1500 and full strength ("2100") at
300K nodes against Levels 3-7; full strength at 2M nodes against Levels 6-8.

## Final match results

```
L2 vs L1: +74 =1 -5 (74.5/80)          L1 vs karballo-500:  +36 =0 -4  (36.0/40)
L3 vs L2: +76 =0 -4 (76.0/80)          L1 vs karballo-1000: +10 =0 -30 (10.0/40)
L4 vs L3: +73 =2 -5 (74.0/80)          L2 vs karballo-500:  +38 =0 -2  (38.0/40)
L5 vs L4: +73 =5 -2 (75.5/80)          L2 vs karballo-1000: +29 =2 -9  (30.0/40)
L6 vs L5: +77 =2 -1 (78.0/80)          L3 vs karballo-500:  +40 =0 -0  (40.0/40)
L7 vs L6: +70 =9 -1 (74.5/80)          L3 vs karballo-1000: +37 =1 -2  (37.5/40)
L8 vs L7: +70 =10 -0 (75.0/80)         L3 vs karballo-1500: +6 =1 -33  (6.5/40)
L1 vs random: +37 =3 -0 (38.5/40)      L3 vs karballo-2100: +0 =1 -39  (0.5/40)
                                       L4 vs karballo-1500: +13 =6 -21 (16.0/40)
                                       L4 vs karballo-2100: +0 =0 -40  (0.0/40)
                                       L5 vs karballo-1500: +22 =4 -14 (24.0/40)
                                       L5 vs karballo-2100: +0 =4 -36  (2.0/40)
                                       L6 vs karballo-1500: +37 =1 -2  (37.5/40)
                                       L6 vs karballo-2100: +4 =3 -33  (5.5/40)
                                       L7 vs karballo-1500: +39 =0 -1  (39.0/40)
                                       L7 vs karballo-2100: +25 =4 -11 (27.0/40)
                                       L6 vs karballo (2M): +1 =3 -36  (2.5/40)
                                       L7 vs karballo (2M): +9 =13 -18 (15.5/40)
                                       L8 vs karballo (2M): +26 =11 -3 (31.5/40)
```
