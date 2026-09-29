#!/usr/bin/env python3
"""Fits one Elo rating per player to the match lines of a Level calibration (docs/levels.md).

    python3 scripts/level-elo.py [tool/build/calibration.txt] [--only anchor|ladder]

Reads lines like "L2 vs L1: +74 =1 -5 ..." (the last line for each pair wins, so a rerun replaces an
older result), fits ratings by maximum likelihood (a Bradley-Terry model on the Elo scale, draws as
half a win), and prints them with L1 = 0. `--only anchor` keeps only games against Karballo, so the
gaps come from a common outside opponent rather than self-play; `--only ladder` keeps Level pairs.
"""
import math
import re
import sys

LINE = re.compile(r"^(\S+) vs (\S+): \+(\d+) =(\d+) -(\d+) ")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    only = sys.argv[sys.argv.index("--only") + 1] if "--only" in sys.argv else None
    if only:
        args = [a for a in args if a != only]
    path = args[0] if args else "tool/build/calibration.txt"
    pairs = {}
    for line in open(path):
        m = LINE.match(line.removeprefix("Calibration: ").strip())
        if not m:
            continue
        a, b, w, d, l = m.group(1), m.group(2), *map(int, m.groups()[2:])
        anchor = a.startswith("karballo") or b.startswith("karballo")
        if b == "random" or (only == "anchor" and not anchor) or (only == "ladder" and anchor):
            continue
        pairs[(a, b)] = (w, d, l)
    players = sorted({p for pair in pairs for p in pair})
    rating = {p: 0.0 for p in players}
    # Gradient ascent on the log-likelihood; the scale is fixed afterwards by L1 = 0.
    for _ in range(20000):
        grad = {p: 0.0 for p in players}
        for (a, b), (w, d, l) in pairs.items():
            n = w + d + l
            expected = 1 / (1 + 10 ** ((rating[b] - rating[a]) / 400))
            g = (w + d / 2) - n * expected
            grad[a] += g
            grad[b] -= g
        for p in players:
            rating[p] += 2.0 * grad[p]
    base = rating.get("L1", 0.0)
    for p in sorted(players, key=lambda p: rating[p]):
        print(f"{p:>14} {rating[p] - base:+7.0f}")


if __name__ == "__main__":
    main()
