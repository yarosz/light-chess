#!/usr/bin/env python3
"""Vendor Pirarucu's pirarucu-common into the Tool, and prove the copy still searches like upstream.

  scripts/vendor-pirarucu.py            regenerate tool/src/main/kotlin/vendor/pirarucu from upstream
  scripts/vendor-pirarucu.py --check    regenerate into a scratch dir and fail if the committed tree differs
  scripts/vendor-pirarucu.py --parity   build upstream and the vendored copy as two plain JVM programs,
                                        run the fixed-depth suite on both, and compare them with each
                                        other and with the pinned table the unit tests read

Upstream is github.com/ratosh/pirarucu at 987dd02 (v3.4.0), cloned into build/pirarucu (gitignored).
The www. host sidesteps a global https-to-SSH rewrite of github.com; git follows the redirect.

Layout. The sources land in tool/src/main/kotlin/vendor/pirarucu/, one directory per package under
`pirarucu` (vendor/pirarucu/search/MainSearch.kt holds package pirarucu.search). The package names stay
upstream's, so every unchanged file is byte-for-byte upstream and diffs stay readable; the directory
stands in for the `pirarucu` root package. Light's plugin scans the tree like our own code, and a build
script may not add source directories, so the copy has to live under src/main/kotlin. Light's extractor
accepts only .kt files there, so upstream's LICENSE and the provenance notes live in vendor/pirarucu/ at
the repo root.

Changes to upstream, and only these (the fixed-depth parity suite guards the search):
  * PlatformSpecific: the `expect object` is replaced with a plain stdlib object (its JVM `actual`
    used java.lang and reflection; the reflective applyConfig, exit, gc, getVersion and formatString
    are dropped because nothing left calls them).
  * Deleted: the UCI front end (uci/), the stdout listener and the EPD tuning helper (util/epd/).
  * SearchOptions: `stop` is @Volatile (written by the UI thread, read by the search thread); it gains
    a node budget and a list of excluded root moves.
  * MainSearch: a node-budget check next to the time check; the ply-0 move loop skips excluded root
    moves (top-N sampling, decision E3); the root records its best move directly, and the iterative
    deepening loop keeps the best move of the last completed iteration, instead of reading them from
    the transposition table.
Each changed file starts with a "modified" notice (GPLv3 section 5a).
"""
from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
UPSTREAM_URL = "https://www.github.com/ratosh/pirarucu.git"
UPSTREAM_COMMIT = "987dd02c6d5cf1fa913ec92262aab42de38c96c2"
UPSTREAM_SHORT = UPSTREAM_COMMIT[:7]
CLONE = ROOT / "build/pirarucu"
COMMON = CLONE / "pirarucu-common/src/main/kotlin/pirarucu"
JVM_PLATFORM = CLONE / "pirarucu-jvm/src/main/kotlin/pirarucu/util/PlatformSpecific.kt"
OUT = ROOT / "tool/src/main/kotlin/vendor/pirarucu"
PARITY_BUILD = ROOT / "scripts/pirarucu-parity"
UPSTREAM_SRC = ROOT / "build/pirarucu-parity/upstream-src"
PINNED = ROOT / "tool/src/test/resources/engine/parity.txt"
PINNED_HEADER = f"""\
# Upstream Pirarucu {UPSTREAM_COMMIT[:7]} at fixed depth, written by scripts/vendor-pirarucu.py --parity
# (delete this file and rerun to re-pin). One search per line: position depth nodes bestmove score.
# Fresh TranspositionTable(16 MB), PawnEvaluationCache(2 MB) and History per search, no time limit.
"""

DELETE = [
    "uci/UciInput.kt",
    "uci/IInputHandler.kt",
    "uci/UciOutput.kt",
    "search/SimpleSearchInfoListener.kt",
    "util/epd",
]

NOTICE = """\
// Modified for the Chess Tool (github.com/yarosz/light-chess), 2026-09-28.
// Original: Pirarucu (github.com/ratosh/pirarucu) at {commit}, GPL-3.0; this file stays GPL-3.0.
// Change: {change}
""".replace("{commit}", UPSTREAM_SHORT)

PLATFORM_SPECIFIC = """\
package pirarucu.util

// Plain-Kotlin replacement for upstream's `expect object` (its JVM `actual` used java.lang and
// reflection). Dropped, as nothing left calls them: applyConfig (reflection on TunableConstants),
// exit, gc, getVersion, formatString.
object PlatformSpecific {

    fun currentTimeMillis(): Long = System.currentTimeMillis()

    fun numberOfTrailingZeros(value: Long): Int = value.countTrailingZeroBits()

    fun numberOfTrailingZeros(value: Int): Int = value.countTrailingZeroBits()

    fun bitCount(value: Long): Int = value.countOneBits()

    fun reverseBytes(value: Long): Long {
        var v = value
        v = ((v ushr 8) and 0x00FF00FF00FF00FFL) or ((v and 0x00FF00FF00FF00FFL) shl 8)
        v = ((v ushr 16) and 0x0000FFFF0000FFFFL) or ((v and 0x0000FFFF0000FFFFL) shl 16)
        return (v ushr 32) or (v shl 32)
    }

    fun arraySort(array: IntArray, start: Int, end: Int) = array.sort(start, end)

    fun arrayFill(array: ShortArray, value: Short) = array.fill(value)

    fun arrayFill(array: IntArray, value: Int) = array.fill(value)

    fun arrayFill(array: LongArray, value: Long) = array.fill(value)

    fun arrayFill(array: Array<IntArray>, value: Int) = array.forEach { it.fill(value) }

    fun arrayFill(array: Array<Array<IntArray>>, value: Int) = array.forEach { arrayFill(it, value) }

    fun arrayCopy(src: IntArray, srcPos: Int, dest: IntArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }

    fun arrayCopy(src: Array<IntArray>, dest: Array<IntArray>) {
        for (i in src.indices) src[i].copyInto(dest[i])
    }

    fun arrayCopy(src: LongArray, srcPos: Int, dest: LongArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }

    fun arrayCopy(src: Array<LongArray>, dest: Array<LongArray>) {
        for (i in src.indices) src[i].copyInto(dest[i])
    }
}
"""

# (file, change note, [(old, new), ...]): each old text must occur exactly once in upstream.
PATCHES: list[tuple[str, str, list[tuple[str, str]]]] = [
    ("search/SearchOptions.kt",
     "@Volatile stop; a node budget and excluded root moves.",
     [(
         "    // Search control\n    var stop = false\n",
         "    // Search control\n"
         "    @Volatile var stop = false\n"
         "    // Stop once this many nodes are searched; 0 means no node budget.\n"
         "    var nodeLimit = 0L\n"
         "    // Root moves the ply-0 move loop skips (top-N sampling).\n"
         "    var excludedRootMoves = IntArray(0)\n",
     )]),
    ("search/MainSearch.kt",
     "a node budget; excluded root moves; the root best move recorded directly.",
     [
         (
             "    val searchInfo = SearchInfo(transpositionTable, history)\n",
             "    val searchInfo = SearchInfo(transpositionTable, history)\n"
             "\n"
             "    // The root's best move and score, recorded by the ply-0 search when it is not stopped.\n"
             "    var rootBestMove = Move.NONE\n"
             "        private set\n"
             "    var rootBestScore = 0\n"
             "        private set\n"
             "\n"
             "    // The best move, score and depth of the last completed iteration (a result inside the\n"
             "    // aspiration window, or a fail high); Move.NONE until depth 1 completes.\n"
             "    var completedMove = Move.NONE\n"
             "        private set\n"
             "    var completedScore = 0\n"
             "        private set\n"
             "    var completedDepth = 0\n"
             "        private set\n"
             "\n"
             "    private fun isExcludedRootMove(move: Int): Boolean {\n"
             "        for (excluded in searchOptions.excludedRootMoves) {\n"
             "            if (excluded == move) {\n"
             "                return true\n"
             "            }\n"
             "        }\n"
             "        return false\n"
             "    }\n",
         ),
         (
             "            searchOptions.stop = true\n            return 0\n        }\n\n"
             "        val currentAlpha",
             "            searchOptions.stop = true\n            return 0\n        }\n"
             "        if (!rootNode &&\n"
             "            searchOptions.nodeLimit > 0L &&\n"
             "            searchInfo.searchNodes >= searchOptions.nodeLimit\n"
             "        ) {\n"
             "            searchOptions.stop = true\n"
             "            return 0\n"
             "        }\n\n"
             "        val currentAlpha",
         ),
         (
             "            if (move != ttMove && !board.isLegalMove(move)) {\n                continue\n            }\n",
             "            if (move != ttMove && !board.isLegalMove(move)) {\n                continue\n            }\n"
             "            if (rootNode && isExcludedRootMove(move)) {\n                continue\n            }\n",
         ),
         (
             "        if (!searchOptions.stop) {\n            transpositionTable.save(",
             "        if (rootNode && !searchOptions.stop) {\n"
             "            rootBestMove = bestMove\n"
             "            rootBestScore = bestScore\n"
             "        }\n\n"
             "        if (!searchOptions.stop) {\n            transpositionTable.save(",
         ),
         (
             "    fun search(board: Board) {\n        searchInfo.reset()\n",
             "    fun search(board: Board) {\n        searchInfo.reset()\n"
             "        rootBestMove = Move.NONE\n"
             "        rootBestScore = 0\n"
             "        completedMove = Move.NONE\n"
             "        completedScore = 0\n"
             "        completedDepth = 0\n",
         ),
         (
             "                searchInfo.save(board)\n                searchInfoListener.searchInfo(",
             "                searchInfo.save(board)\n"
             "                if (rootBestMove != Move.NONE && (score > alpha || alpha == EvalConstants.SCORE_MIN)) {\n"
             "                    completedMove = rootBestMove\n"
             "                    completedScore = score\n"
             "                    completedDepth = depth\n"
             "                }\n"
             "                searchInfoListener.searchInfo(",
         ),
     ]),
]


def run(*cmd: str, cwd: pathlib.Path | None = None) -> str:
    return subprocess.run(cmd, cwd=cwd, check=True, text=True, capture_output=True).stdout.strip()


def fetch() -> None:
    if not (CLONE / ".git").exists():
        CLONE.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", UPSTREAM_URL, str(CLONE)], check=True)
    if run("git", "rev-parse", "HEAD", cwd=CLONE) != UPSTREAM_COMMIT:
        subprocess.run(["git", "-C", str(CLONE), "checkout", "--quiet", UPSTREAM_COMMIT], check=True)
    head = run("git", "rev-parse", "HEAD", cwd=CLONE)
    if head != UPSTREAM_COMMIT:
        sys.exit(f"vendor-pirarucu: build/pirarucu is at {head}, not {UPSTREAM_COMMIT}")
    if run("git", "status", "--porcelain", cwd=CLONE):
        sys.exit("vendor-pirarucu: build/pirarucu has local changes; delete it and rerun")


def generate(out: pathlib.Path) -> None:
    if out.exists():
        shutil.rmtree(out)
    shutil.copytree(COMMON, out)
    for rel in DELETE:
        path = out / rel
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
    for empty in sorted((p for p in out.rglob("*") if p.is_dir()), reverse=True):
        if not any(empty.iterdir()):
            empty.rmdir()
    (out / "util/PlatformSpecific.kt").write_text(
        NOTICE.format(change="the `expect object` replaced with a plain stdlib object.") + PLATFORM_SPECIFIC
    )
    for rel, change, edits in PATCHES:
        path = out / rel
        text = path.read_text()
        for old, new in edits:
            count = text.count(old)
            if count != 1:
                sys.exit(f"vendor-pirarucu: {rel}: expected one match, found {count}:\n{old}")
            text = text.replace(old, new)
        path.write_text(NOTICE.format(change=change) + text)


def tree(root: pathlib.Path) -> dict[str, bytes]:
    return {str(p.relative_to(root)): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


def check() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        fresh = pathlib.Path(tmp) / "pirarucu"
        generate(fresh)
        want, have = tree(fresh), tree(OUT) if OUT.exists() else {}
    diff = sorted(k for k in want.keys() | have.keys() if want.get(k) != have.get(k))
    license_copy = ROOT / "vendor/pirarucu/LICENSE"
    if not license_copy.exists() or license_copy.read_bytes() != (CLONE / "LICENSE").read_bytes():
        diff.append(str(license_copy.relative_to(ROOT)))
    for k in diff:
        print(f"vendor-pirarucu: differs: {k}", file=sys.stderr)
    if diff:
        print("vendor-pirarucu: FAIL the committed tree is not what the script generates", file=sys.stderr)
        return 1
    print(f"vendor-pirarucu: OK {len(want)} files match upstream {UPSTREAM_SHORT} plus the listed changes")
    return 0


def prepare_upstream() -> None:
    """Upstream as a plain JVM module: pirarucu-common as is, its expect object swapped for the JVM
    actual with `actual` stripped (a plain JVM module has no expect/actual)."""
    if UPSTREAM_SRC.exists():
        shutil.rmtree(UPSTREAM_SRC)
    shutil.copytree(COMMON, UPSTREAM_SRC / "pirarucu")
    actual = JVM_PLATFORM.read_text().replace("actual ", "")
    (UPSTREAM_SRC / "pirarucu/util/PlatformSpecific.kt").write_text(actual)


def parity() -> int:
    prepare_upstream()
    gradle = [str(ROOT / "gradlew"), "-p", str(PARITY_BUILD), "--console=plain", "-q"]
    subprocess.run(gradle + [":upstream:installDist", ":vendored:installDist"], check=True)
    results = {}
    for side in ("upstream", "vendored"):
        exe = ROOT / "build/pirarucu-parity" / side / "build/install" / side / "bin" / side
        results[side] = run(str(exe))
        print(f"--- {side}\n{results[side]}")
    if not PINNED.exists():
        PINNED.parent.mkdir(parents=True, exist_ok=True)
        PINNED.write_text(PINNED_HEADER + results["upstream"] + "\n")
        print(f"vendor-pirarucu: pinned upstream's table in {PINNED.relative_to(ROOT)}")
    pinned = "\n".join(
        line for line in PINNED.read_text().splitlines() if line and not line.startswith("#")
    )
    ok = results["upstream"] == results["vendored"] == pinned
    if results["upstream"] != results["vendored"]:
        print("vendor-pirarucu: FAIL vendored node counts differ from upstream", file=sys.stderr)
    if results["upstream"] != pinned:
        print(f"vendor-pirarucu: FAIL {PINNED.relative_to(ROOT)} differs from upstream", file=sys.stderr)
    if ok:
        print(f"vendor-pirarucu: OK parity: upstream = vendored = pinned ({len(pinned.splitlines())} searches)")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="fail if the committed tree differs")
    mode.add_argument("--parity", action="store_true", help="compare node counts with upstream")
    args = parser.parse_args()
    fetch()
    if args.check:
        return check()
    if args.parity:
        return parity()
    generate(OUT)
    shutil.copyfile(CLONE / "LICENSE", ROOT / "vendor/pirarucu/LICENSE")
    print(f"vendor-pirarucu: wrote {len(tree(OUT))} files to {OUT.relative_to(ROOT)}")
    return check()


if __name__ == "__main__":
    sys.exit(main())
