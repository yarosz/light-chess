#!/usr/bin/env python3
"""Fits Elo ratings to the match lines of a Level calibration (docs/levels.md).

    python3 scripts/level-elo.py [calibration.txt ...] [--only anchor|ladder]
    python3 scripts/level-elo.py [calibration.txt ...] --stockfish

Reads lines like "L2 vs L1: +74 =1 -5 ..." and fits ratings by maximum likelihood (a Bradley-Terry
model on the Elo scale, draws as half a win).

Without --stockfish, the last line for each pair wins (a rerun replaces an older result) and the
ratings print with L1 = 0. `--only anchor` keeps only games against Karballo, so the gaps come from a
common outside opponent rather than self-play; `--only ladder` keeps Level pairs.

--stockfish keeps only games against Stockfish's limiter ("L3 vs sf-1600") and adds up every line for
a pair, so separate runs (with separate rounds, hence seeds) pool. Each anchor is fixed at its UCI_Elo
and each Level gets its own rating with a 95% likelihood-ratio interval. Consistency, per Level: the
deviance G2 of its pairings around the fitted rating, against chi-square with (anchors - 1) degrees of
freedom. Across Levels: the anchors fitted freely (their mean pinned to their labels' mean) beside
their labels. Draws count as half a win in a binomial likelihood, which overstates the variance when
there are draws, so the intervals are slightly conservative.
"""
import math
import re
import sys

LINE = re.compile(r"^(\S+) vs (\S+): \+(\d+) =(\d+) -(\d+) ")
K = math.log(10) / 400


def expected(ra: float, rb: float) -> float:
    return 1 / (1 + 10 ** ((rb - ra) / 400))


def loglik(pairs, rating) -> float:
    total = 0.0
    for (a, b), (w, d, l) in pairs.items():
        n, s = w + d + l, w + d / 2
        e = min(max(expected(rating[a], rating[b]), 1e-12), 1 - 1e-12)
        total += s * math.log(e) + (n - s) * math.log(1 - e)
    return total


def fit(pairs, free, fixed, iterations=20000, step=2.0):
    """Gradient ascent over the players in [free]; [fixed] maps players to their ratings."""
    rating = {p: 0.0 for pair in pairs for p in pair}
    rating.update(fixed)
    for p in free:
        rating[p] = sum(fixed.values()) / len(fixed) if fixed else 0.0
    for _ in range(iterations):
        grad = {p: 0.0 for p in free}
        for (a, b), (w, d, l) in pairs.items():
            g = (w + d / 2) - (w + d + l) * expected(rating[a], rating[b])
            if a in grad:
                grad[a] += g
            if b in grad:
                grad[b] -= g
        for p in free:
            rating[p] += step * grad[p] / max(1, sum(w + d + l for pair, (w, d, l) in pairs.items() if p in pair)) * 40
    return rating


def chi2_sf(x: float, df: int) -> float:
    """P(chi-square with df degrees of freedom > x), by the recurrence Q(k + 2) = Q(k) + term."""
    if df <= 0:
        return 1.0
    q = math.erfc(math.sqrt(x / 2)) if df % 2 else math.exp(-x / 2)
    k = 1 if df % 2 else 2
    while k < df:
        q += (x / 2) ** (k / 2) * math.exp(-x / 2) / math.gamma(k / 2 + 1)
        k += 2
    return q


def interval(pairs, level, anchors, best):
    """The 95% likelihood-ratio interval of one Level's rating, the anchors fixed."""
    own = {pair: v for pair, v in pairs.items() if level in pair}
    rating = dict(anchors)

    def ll(r):
        rating[level] = r
        return loglik(own, rating)

    top = ll(best)
    out = []
    for direction in (-1, 1):
        lo, hi = 0.0, 1500.0
        for _ in range(60):
            mid = (lo + hi) / 2
            if top - ll(best + direction * mid) < 1.92:
                lo = mid
            else:
                hi = mid
        out.append(best + direction * lo)
    return out


def stockfish(pairs) -> None:
    anchors = {p: float(p.split("-")[1]) for pair in pairs for p in pair if p.startswith("sf-")}
    levels = sorted({p for pair in pairs for p in pair if p.startswith("L")}, key=lambda p: int(p[1:]))
    print("Level  games  score   Elo   95% interval   anchors (score)                      G2   df  p")
    for level in levels:
        own = {pair: v for pair, v in pairs.items() if level in pair}
        rating = fit(own, [level], anchors)
        best = rating[level]
        lo, hi = interval(pairs, level, anchors, best)
        n = sum(w + d + l for w, d, l in own.values())
        s = sum(w + d / 2 for w, d, l in own.values())
        g2 = 0.0
        for (a, b), (w, d, l) in own.items():
            m, sc = w + d + l, w + d / 2
            e = expected(rating[a], rating[b])
            for obs, exp in ((sc, m * e), (m - sc, m * (1 - e))):
                if obs > 0:
                    g2 += 2 * obs * math.log(obs / exp)
        g2, df = max(g2, 0.0), len(own) - 1
        detail = "  ".join(
            f"{(b if a == level else a)[3:]} ({(w + d / 2) if a == level else (l + d / 2):g}/{w + d + l})"
            for (a, b), (w, d, l) in sorted(own.items(), key=lambda kv: anchors[kv[0][1] if kv[0][0] == level else kv[0][0]])
        )
        print(f"{level:>5} {n:6} {s:6.1f} {best:6.0f}  [{lo:5.0f}, {hi:5.0f}]   {detail:<36} {g2:5.2f} {df:3} {chi2_sf(g2, df):.2f}")
    # The free fit needs a connected set of players: Levels that share anchors with other Levels.
    shared = {a for a in anchors if sum(1 for pair in pairs if a in pair) > 1}
    component = {p for pair in pairs if pair[0] in shared or pair[1] in shared for p in pair}
    free_pairs = {pair: v for pair, v in pairs.items() if pair[0] in component and pair[1] in component}
    if not free_pairs:
        return
    free = free_fit(free_pairs)
    free_anchors = [a for a in anchors if a in component]
    shift = sum(anchors[a] - free[a] for a in free_anchors) / len(free_anchors)
    print("\nAnchors played by more than one Level, fitted freely (mean pinned to their labels' mean):")
    for a in sorted(free_anchors, key=anchors.get):
        print(f"{a:>8}  label {anchors[a]:5.0f}  fitted {free[a] + shift:5.0f}  ({free[a] + shift - anchors[a]:+4.0f})")
    print("Levels on that free scale: " + "  ".join(f"{p} {free[p] + shift:.0f}" for p in levels if p in component))


def free_fit(pairs):
    """Every player's rating from the results alone (Hunter's MM algorithm), mean 0."""
    players = sorted({p for pair in pairs for p in pair})
    gamma = {p: 1.0 for p in players}
    wins = {p: 0.0 for p in players}
    for (a, b), (w, d, l) in pairs.items():
        wins[a] += w + d / 2
        wins[b] += l + d / 2
    for _ in range(5000):
        new = {}
        for p in players:
            denom = sum((w + d + l) / (gamma[a] + gamma[b]) for (a, b), (w, d, l) in pairs.items() if p in (a, b))
            new[p] = max(wins[p], 0.25) / denom
        mean = sum(math.log(g) for g in new.values()) / len(new)
        gamma = {p: math.exp(math.log(g) - mean) for p, g in new.items()}
    return {p: 400 * math.log10(g) for p, g in gamma.items()}


def main() -> None:
    argv = sys.argv[1:]
    only = argv[argv.index("--only") + 1] if "--only" in argv else None
    paths = [a for a in argv if not a.startswith("--") and a != only] or ["tool/build/calibration.txt"]
    sf = "--stockfish" in argv
    pairs = {}
    for path in paths:
        for line in open(path):
            m = LINE.match(line.removeprefix("Calibration: ").strip())
            if not m:
                continue
            a, b, w, d, l = m.group(1), m.group(2), *map(int, m.groups()[2:])
            if sf:
                if a.startswith("sf-") or b.startswith("sf-"):
                    old = pairs.get((a, b), (0, 0, 0))
                    pairs[(a, b)] = (old[0] + w, old[1] + d, old[2] + l)
                continue
            if a.startswith("sf-") or b.startswith("sf-"):
                continue
            anchor = a.startswith("karballo") or b.startswith("karballo")
            if b == "random" or (only == "anchor" and not anchor) or (only == "ladder" and anchor):
                continue
            pairs[(a, b)] = (w, d, l)
    if sf:
        stockfish(pairs)
        return
    players = sorted({p for pair in pairs for p in pair})
    rating = {p: 0.0 for p in players}
    # Gradient ascent on the log-likelihood; the scale is fixed afterwards by L1 = 0.
    for _ in range(20000):
        grad = {p: 0.0 for p in players}
        for (a, b), (w, d, l) in pairs.items():
            n = w + d + l
            g = (w + d / 2) - n * expected(rating[a], rating[b])
            grad[a] += g
            grad[b] -= g
        for p in players:
            rating[p] += 2.0 * grad[p]
    base = rating.get("L1", 0.0)
    for p in sorted(players, key=lambda p: rating[p]):
        print(f"{p:>14} {rating[p] - base:+7.0f}")


if __name__ == "__main__":
    main()
