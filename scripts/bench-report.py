#!/usr/bin/env python3
"""Summarise ChessBench lines (the engine benchmark, tool/src/benchmark/.../bench/BenchSuite.kt) as P50/P90.

  scripts/bench-report.py [title] < lines

Reads logcat output or plain lines; only the text after "ChessBench: " (or the whole line) counts.
Warm-up searches (run 0) are left out. Percentiles are nearest-rank, as in light-reader's perf.sh.
"""
import collections
import math
import re
import sys

PAIR = re.compile(r"(\w+)=(\S+)")


def pct(values, q):
    ordered = sorted(values)
    return ordered[max(1, math.ceil(q * len(ordered))) - 1]


def row(values, fmt="{:>9,}"):
    if not values:
        return " " * 21 + "-"
    return " ".join(fmt.format(v) for v in (pct(values, 0.5), pct(values, 0.9), len(values)))


def main():
    title = sys.argv[1] if len(sys.argv) > 1 else "ChessBench"
    start, done, searches = {}, None, []
    for raw in sys.stdin:
        line = raw.split("ChessBench: ", 1)[-1].strip()
        fields = dict(PAIR.findall(line))
        if line.startswith("start "):
            start = fields
        elif line.startswith("search ") and fields.get("warmup") == "false":
            searches.append(fields)
        elif line.startswith("done"):
            done = fields
    if not searches or done is None:
        sys.exit("bench-report: no complete ChessBench run in the input")

    print(f"{title}: {start.get('runs')} runs (warm-up left out), JVM max heap {start.get('maxMemoryMb')} MB, "
          f"{start.get('processors')} processors, used heap at the end {done.get('usedMemoryMb')} MB")
    for kind, label in (("depth", f"fixed depth {start.get('depth')}"), ("wall", f"think time {start.get('wallMs')} ms")):
        rows = [s for s in searches if s["kind"] == kind]
        print(f"\n{label}")
        print(f"{'position':<10} {'ms P50/P90/n':>29}  {'nps P50/P90/n':>29}  {'depth P50/P90/n':>29}")
        for name in dict.fromkeys(s["pos"] for s in rows):
            mine = [s for s in rows if s["pos"] == name]
            print(f"{name:<10} {row([int(s['ms']) for s in mine])}  {row([int(s['nps']) for s in mine])}  "
                  f"{row([int(s['depth']) for s in mine])}")
        print(f"{'all':<10} {row([int(s['ms']) for s in rows])}  {row([int(s['nps']) for s in rows])}  "
              f"{row([int(s['depth']) for s in rows])}")

    reached = collections.defaultdict(list)
    for s in searches:
        if s["kind"] == "depth":
            for pair in filter(None, s.get("ttd", "").split(",")):
                d, ms = pair.split(":")
                reached[int(d)].append(int(ms))
    print("\ntime to depth, ms, all positions (P50/P90/n)")
    for d in sorted(reached):
        if d % 2 == 0 or d == max(reached):
            print(f"  depth {d:>2}  {row(reached[d])}")

    cores = collections.Counter(c for s in searches for c in s.get("cpu", "").split(",") if c)
    print("\nengine thread CPU core (samples): " + ", ".join(f"{c}: {n}" for c, n in sorted(cores.items())))
    threads = collections.Counter(
        f"{name} tid {tid}" for s in searches for name in s.get("thread", "?").split(",") for tid in s.get("tid", "?").split(",")
    )
    print("searching thread (searches): " + ", ".join(f"{t}: {n}" for t, n in sorted(threads.items())))


if __name__ == "__main__":
    main()
