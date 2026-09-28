#!/usr/bin/env python3
"""Build the Puzzle Pack from the pinned Lichess puzzle dump (ADR 0003, decision log A1, A2).

    scripts/build-pack.py [--dump-dir DIR] [--out DIR] [--allow-new-dump]

DIR for the dump defaults to $LICHESS_DUMP_DIR, else build/lichess under the repo root (gitignored).
The dump is downloaded there once and reused. It is never written into the repo's tracked tree.

Output: one file per Band, tool/src/main/assets/pack/<band>.txt, with lines
`id;FEN;UCI moves;rating;RD;themes`, sorted by rating then id, plus manifest.json. The output depends
only on the dump and the constants below: two runs give byte-identical files.

Uses the Python standard library and the `zstd` command-line tool (for streaming decompression).
The Kotlin rules core checks every Puzzle afterwards (PackTest, `./gradlew :tool:testDebugUnitTest`).
"""

import argparse
import csv
import hashlib
import io
import json
import math
import os
import random
import shutil
import subprocess
import sys
import urllib.request
from collections import Counter, defaultdict
from email.utils import parsedate_to_datetime
from pathlib import Path

DUMP_URL = "https://database.lichess.org/lichess_db_puzzle.csv.zst"
DUMP_NAME = "lichess_db_puzzle.csv.zst"
# The pinned dump (A2). A different dump fails the build unless --allow-new-dump is given, and then
# the new date and SHA-256 must be pinned here in the same change.
PINNED_DATE = "2026-09-09"
PINNED_SHA256 = "95fd454bec9efe8f940d5863d5db4c57474f281a865834997bd8cb5d6a149bb9"

# The quality filter (A1). Only 22 Bands (500 to 2600) have any Puzzle that passes it, and only 15
# have 2,000, so a Band short of 2,000 is filled from the looser filter R1.4 named, best tier first.
# Set FILL to None for the strict filter alone.
STRICT = {"minPopularity": 95, "minPlays": 1000, "maxRatingDeviation": 80}
FILL = {"minPopularity": 85, "minPlays": 500, "maxRatingDeviation": 90}
PER_BAND = 2000
LOWEST_BAND = 400   # Puzzles rated below 400 join this Band.
TOP_BAND = 2800     # Every Puzzle rated 2800 or more is in this Band, uncapped.
SEED = 20260909

# Themes that say how long a Puzzle is, what phase or source it comes from, or how big the win is:
# they don't describe the tactic, so theme mixing ignores them.
GENERIC_THEMES = {
    "advantage", "crushing", "equality", "mate", "oneMove", "short", "long", "veryLong",
    "opening", "middlegame", "endgame", "master", "masterVsMaster", "superGM", "player",
}


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_dump(dump_dir: Path) -> tuple[Path, str]:
    """Downloads the dump unless it is already there. Returns its path and Last-Modified date."""
    dump_dir.mkdir(parents=True, exist_ok=True)
    dump = dump_dir / DUMP_NAME
    date_file = dump_dir / (DUMP_NAME + ".last-modified")
    if not dump.exists() or not date_file.exists():
        print(f"build-pack: downloading {DUMP_URL}", file=sys.stderr)
        partial = dump_dir / (DUMP_NAME + ".part")
        with urllib.request.urlopen(DUMP_URL) as response, open(partial, "wb") as out:
            modified = parsedate_to_datetime(response.headers["Last-Modified"]).date().isoformat()
            shutil.copyfileobj(response, out, 1 << 20)
        partial.rename(dump)
        date_file.write_text(modified + "\n")
    return dump, date_file.read_text().strip()


def band_of(rating: int) -> int:
    return min(TOP_BAND, max(LOWEST_BAND, rating // 100 * 100))


def passes(row: dict, rule: dict | None) -> bool:
    return rule is not None and (int(row["Popularity"]) >= rule["minPopularity"]
                                 and int(row["NbPlays"]) >= rule["minPlays"]
                                 and int(row["RatingDeviation"]) <= rule["maxRatingDeviation"])


def read_candidates(dump: Path) -> tuple[list[dict], dict]:
    """Streams the CSV out of zstd, keeps the Puzzles that pass a filter, one per source game."""
    stats = Counter()
    by_game: dict[str, dict] = {}
    proc = subprocess.Popen(["zstd", "-dcq", str(dump)], stdout=subprocess.PIPE)
    reader = csv.DictReader(io.TextIOWrapper(proc.stdout, encoding="utf-8", newline=""))
    for row in reader:
        stats["rows"] += 1
        if passes(row, STRICT):
            tier = 1
        elif passes(row, FILL):
            tier = 2
        else:
            continue
        stats[f"passedTier{tier}"] += 1
        puzzle = {
            "id": row["PuzzleId"],
            "fen": row["FEN"],
            "moves": row["Moves"].split(),
            "rating": int(row["Rating"]),
            "rd": int(row["RatingDeviation"]),
            "plays": int(row["NbPlays"]),
            "tier": tier,
            "themes": sorted(row["Themes"].split()),
        }
        for field in ("id", "fen"):
            if ";" in puzzle[field]:
                raise SystemExit(f"build-pack: ';' inside {field} of puzzle {puzzle['id']}")
        # https://lichess.org/<game>[/black]#<ply>: the game id alone names the source game.
        game = row["GameUrl"].split("#", 1)[0].removeprefix("https://lichess.org/").split("/", 1)[0]
        # One Puzzle per source game: the better tier, then the most played, then the lowest id, so
        # the choice doesn't depend on the dump's row order.
        def rank(p):
            return p["tier"], -p["plays"], p["id"]
        kept = by_game.get(game)
        if kept is None or rank(puzzle) < rank(kept):
            by_game[game] = puzzle
    if proc.wait() != 0:
        raise SystemExit("build-pack: zstd failed")
    stats["onePerGame"] = len(by_game)
    return sorted(by_game.values(), key=lambda p: p["id"]), dict(stats)


def motif(puzzle: dict, frequency: Counter) -> str:
    """The Puzzle's rarest tactical theme within its Band (ties by name), or "other"."""
    themes = [t for t in puzzle["themes"] if t not in GENERIC_THEMES]
    return min(themes, key=lambda t: (frequency[t], t)) if themes else "other"


def sample_band(band: int, pool: list[dict]) -> list[dict]:
    """PER_BAND Puzzles (all of them in the top Band): the strict tier first, then the fill tier."""
    if band == TOP_BAND:
        return pool
    strict = [p for p in pool if p["tier"] == 1]
    if len(strict) >= PER_BAND:
        return mix(f"{band}-1", strict, PER_BAND)
    fill = [p for p in pool if p["tier"] == 2]
    return strict + mix(f"{band}-2", fill, PER_BAND - len(strict))


def mix(label: str, pool: list[dict], count: int) -> list[dict]:
    """Picks count Puzzles, spread over their motifs, at random within each motif."""
    if len(pool) <= count:
        return pool
    frequency = Counter(t for p in pool for t in p["themes"])
    groups = defaultdict(list)
    for p in pool:
        groups[motif(p, frequency)].append(p)
    rng = random.Random(f"{SEED}-{label}")
    names = sorted(groups)
    for name in names:
        rng.shuffle(groups[name])
    # Shares in proportion to the square root of each motif's size: common motifs stay common, rare
    # ones get more than their proportion. A motif too small for its share gives the rest away.
    quota = {name: 0 for name in names}
    left = count
    while left > 0:
        open_names = [n for n in names if quota[n] < len(groups[n])]
        weight = {n: math.sqrt(len(groups[n])) for n in open_names}
        total = sum(weight.values())
        given = 0
        for n in open_names:
            take = min(int(left * weight[n] / total), len(groups[n]) - quota[n])
            quota[n] += take
            given += take
        if given == 0:  # Only remainders are left: one each, the largest weights first.
            for n in sorted(open_names, key=lambda n: (-weight[n], n))[:left]:
                quota[n] += 1
                given += 1
        left -= given
    return [p for name in names for p in groups[name][:quota[name]]]


def line(p: dict) -> str:
    return ";".join([p["id"], p["fen"], " ".join(p["moves"]), str(p["rating"]), str(p["rd"]), " ".join(p["themes"])])


def canonical_json(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dump-dir", type=Path,
                        default=Path(os.environ.get("LICHESS_DUMP_DIR", repo_root() / "build" / "lichess")))
    parser.add_argument("--out", type=Path, default=repo_root() / "tool" / "src" / "main" / "assets" / "pack")
    parser.add_argument("--allow-new-dump", action="store_true",
                        help="build from a dump other than the pinned one (then pin it)")
    args = parser.parse_args()
    if shutil.which("zstd") is None:
        raise SystemExit("build-pack: needs the zstd command-line tool")

    dump, dump_date = fetch_dump(args.dump_dir)
    dump_sha = sha256_file(dump)
    if (dump_sha, dump_date) != (PINNED_SHA256, PINNED_DATE):
        message = f"dump {dump_date} sha256 {dump_sha} is not the pinned {PINNED_DATE} {PINNED_SHA256}"
        if not args.allow_new_dump:
            raise SystemExit(f"build-pack: {message} (--allow-new-dump to build anyway)")
        print(f"build-pack: warning: {message}", file=sys.stderr)

    candidates, stats = read_candidates(dump)
    pools = defaultdict(list)
    for p in candidates:
        pools[band_of(p["rating"])].append(p)

    args.out.mkdir(parents=True, exist_ok=True)
    for old in list(args.out.glob("*.txt")) + list(args.out.glob("manifest.json")):
        old.unlink()
    bands = []
    for band in sorted(pools):
        chosen = sorted(sample_band(band, pools[band]), key=lambda p: (p["rating"], p["id"]))
        name = f"{band:04d}.txt"
        data = "".join(line(p) + "\n" for p in chosen).encode("utf-8")
        (args.out / name).write_bytes(data)
        bands.append({
            "band": band,
            "file": name,
            "puzzles": len(chosen),
            "available": len(pools[band]),
            "fromFill": sum(1 for p in chosen if p["tier"] == 2),
            "minRating": chosen[0]["rating"],
            "maxRating": chosen[-1]["rating"],
            "bytes": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
        })

    manifest = {
        "schemaVersion": 1,
        "source": {"url": DUMP_URL, "date": dump_date, "sha256": dump_sha, "licence": "CC0-1.0 (lichess.org)"},
        "filter": {
            "strict": STRICT,
            "fill": FILL,
            "onePerGame": True,
            "perBand": PER_BAND,
            "lowestBand": LOWEST_BAND,
            "topBand": TOP_BAND,
            "genericThemes": sorted(GENERIC_THEMES),
        },
        "seed": SEED,
        "counts": stats,
        "puzzles": sum(b["puzzles"] for b in bands),
        "bands": bands,
    }
    # The Pack SHA-256 is over the canonical JSON of every other manifest field (keys sorted, no
    # spaces), so it covers each Band file through its sha256.
    manifest["packSha256"] = hashlib.sha256(canonical_json(manifest)).hexdigest()
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=1, sort_keys=True) + "\n")

    total = sum(b["bytes"] for b in bands)
    largest = max(bands, key=lambda b: b["bytes"])
    print(f"build-pack: {manifest['puzzles']} puzzles in {len(bands)} bands, {total} bytes, "
          f"largest {largest['file']} {largest['bytes']} bytes, pack {manifest['packSha256']}")


if __name__ == "__main__":
    main()
