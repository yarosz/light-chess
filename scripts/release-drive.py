#!/usr/bin/env python3
"""Drive the Chess Tool on the Chess emulator for scripts/release-check.sh.

  release-drive.py skip           answer the seed screen with Skip
  release-drive.py solve          play the user's Moves of the Puzzle on screen, from the Pack
  release-drive.py next           tap Next and wait for the next Puzzle
  release-drive.py state          print the rating, Missed count and rated history (Past puzzles) as JSON
  release-drive.py about          open Home > About and print its text
  release-drive.py wait TEXT      wait until TEXT shows
  release-drive.py shot PATH      save a screenshot

The Puzzle on screen is read from the save file when the build is debuggable (run-as), else from
the "first puzzle drawn" log line, so `solve` works once per launch on a release build. Every input
first checks that the Tool holds the window focus. The emulator is found by AVD name
(scripts/chess-emu.sh), never by position in `adb devices`.
"""
import json, os, re, subprocess, sys, time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "..", "tool", "src", "main", "assets", "pack")
PKG = "com.yarosz.chess"
# The board's squares on a 1080 px wide LP3-shaped screen: 12 dp from the top, 24 dp from the left, 39 dp squares.
LEFT, TOP, CELL = 72, 36, 117


def adb(*args, binary=False):
    out = subprocess.run([os.path.join(HERE, "chess-emu.sh"), *args], capture_output=True)
    if out.returncode != 0 and not binary:
        return None
    return out.stdout if binary else out.stdout.decode()


def focused():
    for _ in range(20):
        line = next((l for l in (adb("shell", "dumpsys", "window") or "").splitlines() if "mCurrentFocus" in l), "")
        if PKG in line:
            return
        if "ImmersiveModeConfirmation" in line:
            tap_label("GOT IT", check=False)
        time.sleep(0.5)
    sys.exit(f"release-drive: the Tool doesn't hold the focus: {line.strip()}")


def nodes():
    for _ in range(5):
        xml = (adb("exec-out", "uiautomator", "dump", "/dev/tty") or "").split("UI hierchary dumped")[0]
        if xml.lstrip().startswith("<?xml"):
            break
        time.sleep(0.5)
    else:
        sys.exit("release-drive: uiautomator dump failed")
    for n in ET.fromstring(xml).iter("node"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        text = n.get("text", "") or n.get("content-desc", "")
        if text:
            yield text, ((x1 + x2) // 2, (y1 + y2) // 2)


def texts():
    return [t for t, _ in nodes()]


def wait_for(pattern, seconds=15):
    deadline = time.time() + seconds
    while time.time() < deadline:
        for t in texts():
            if re.search(pattern, t):
                return t
        time.sleep(0.5)
    sys.exit(f"release-drive: timed out waiting for /{pattern}/; screen shows {texts()}")


def tap(x, y, check=True):
    if check:
        focused()
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.4)


def tap_label(label, check=True):
    hits = [c for t, c in nodes() if t == label] or [c for t, c in nodes() if label in t]
    if not hits:
        sys.exit(f'release-drive: nothing labelled "{label}" on screen: {texts()}')
    tap(*hits[0], check=check)


def puzzle_id():
    save = adb("shell", "run-as", PKG, "cat", "files/puzzles.json")
    if save and save.lstrip().startswith("{"):
        return json.loads(save)["current"]["id"]
    log = adb("logcat", "-d", "-s", "ChessPerf:I") or ""
    ids = re.findall(r"first puzzle drawn .*id=(\S+),", log)
    if not ids:
        sys.exit("release-drive: no Puzzle id in the save or the log")
    return ids[-1]


def line_of(pid):
    for name in sorted(os.listdir(PACK)):
        if name.endswith(".txt"):
            with open(os.path.join(PACK, name)) as f:
                for line in f:
                    if line.startswith(pid + ";"):
                        return line.strip().split(";")
    sys.exit(f"release-drive: {pid} is not in the Pack")


def square(sq, solver):
    f, r = ord(sq[0]) - ord("a"), int(sq[1]) - 1
    col, row = (f, 7 - r) if solver == "white" else (7 - f, r)
    return LEFT + col * CELL + CELL // 2, TOP + row * CELL + CELL // 2


def play(uci, solver):
    tap(*square(uci[:2], solver))
    tap(*square(uci[2:4], solver))
    if len(uci) == 5:  # the picker stacks queen, rook, bishop, knight from the promotion square inward
        step = -1 if uci[3] == "8" else 1
        tap(*square(uci[2] + str(int(uci[3]) + step * "qrbn".index(uci[4])), solver))


def solve():
    wait_for(r"to move|Tap a piece")
    pid = puzzle_id()
    fields = line_of(pid)
    solver = "black" if fields[1].split()[1] == "w" else "white"  # the setup Move is the opponent's
    moves = fields[2].split()
    time.sleep(1.2)  # the setup Move: 500 ms hold, 250 ms slide
    for uci in moves[1::2]:
        play(uci, solver)
        time.sleep(1.0)  # the reply lands 300 ms after the user's Move, then slides for 250 ms
    result = wait_for(r"^Solved")
    print(json.dumps({"puzzle": pid, "result": result}, ensure_ascii=False))


# The Puzzles page's first row (N12), whatever it reads: each opens the Puzzle board.
PUZZLES_START = ("Continue puzzle", "Next puzzle", "Back to the rated puzzle", "Start")


def open_list():
    """From the puzzle screen to the list with the rating and Missed: the Puzzles page since N16
    (back from the Puzzle), Home in a Navigation D build, the puzzle Menu before that (an older
    build, for `upgrade`)."""
    if any(t == "Menu" for t in texts()):
        tap_label("Menu")
    else:
        focused()
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)
    wait_for(r"^Player Rating ·")


def has_label(label):
    return any(t == label for t in texts())


def swipe(up=True):
    """One drag of 500 px in 300 ms on the list: up shows the rows below, down the rows above. It is
    fast enough for Compose to fling on, so it may pass more than a screen; `scroll_to` checks the
    tree after each."""
    focused()
    start, end = ("900", "400") if up else ("400", "900")
    adb("shell", "input", "swipe", "540", start, "540", end, "300")
    time.sleep(0.6)


def scroll_to(label, up=True, tries=6):
    """Drag the list until a row labelled `label` is in uiautomator's tree, stopping once a drag shows
    nothing new (the list's end). uiautomator leaves out a Compose row wholly off screen. Home's five
    rows fit since N11 and return at once; a Navigation D build's eight did not (About below the
    fold), and an older build's puzzle Menu fits."""
    for _ in range(tries):
        if has_label(label):
            return
        before = texts()
        swipe(up)
        if texts() == before:
            break
    if not has_label(label):
        sys.exit(f'release-drive: no "{label}" row after scrolling: {texts()}')


def history_rows():
    return [t for t in texts() if re.match(r"^\d+ · (Solved|Failed|Hinted)", t)]


def state():
    open_list()
    rows = texts()
    rating = next(t for t in rows if t.startswith("Player Rating ·"))
    missed = next(t for t in rows if t.startswith("Missed ·"))
    if "Past puzzles" in rows:
        # N14: the rated history is Past puzzles', its own page on the Puzzles page.
        tap_label("Past puzzles")
        wait_for(r"^\d+ · |^No rated Puzzles yet")
        history = history_rows()
        focused()
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)
    else:
        # A Navigation D build or older: the history is on the Player Rating page.
        tap_label(rating)
        history = history_rows()
    back_to_puzzle()
    print(json.dumps({"rating": rating, "missed": missed, "history": history}, ensure_ascii=False))


def on_puzzle():
    return any(re.search(r"to move|Tap a piece|^Solved|^Failed|^Hinted", t) for t in texts())


def back_to_puzzle():
    """Back, until the puzzle screen shows: from the Puzzles page (N12), its first row; from Home
    (titled "Chess"), its Puzzles row ("Puzzles · 1500?" since N11, "Puzzles" before, scrolled back
    into view on a Navigation D build). Never back from the puzzle screen of an older build, nor from
    Home: either closes the Tool."""
    for _ in range(5):
        if on_puzzle():
            return
        focused()
        rows = texts()
        start = next((t for t in rows if t in PUZZLES_START), None)
        if start:
            tap_label(start)
        elif has_label("Chess"):
            if not any(t.startswith("Puzzles") for t in rows):
                scroll_to("Puzzles", up=False)
            tap_label(next(t for t in texts() if t == "Puzzles" or t.startswith("Puzzles ·")))
        elif "Puzzles" in rows and "Past puzzles" not in rows:
            tap_label("Puzzles")  # an older build's Menu row
        else:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.8)
    if not on_puzzle():
        sys.exit(f"release-drive: not back on the puzzle screen: {texts()}")


def main(argv):
    cmd = argv[0] if argv else "help"
    if cmd == "skip":
        wait_for(r"^Skip$")
        tap_label("Skip")
    elif cmd == "solve":
        solve()
    elif cmd == "next":
        tap_label("Next")
        wait_for(r"to move|Tap a piece")
    elif cmd == "wait":
        wait_for(re.escape(" ".join(argv[1:])))
    elif cmd == "state":
        state()
    elif cmd == "about":
        # This build's Home: back from the Puzzle to the Puzzles page (N16), then to Home, whose five
        # rows fit (N11). Back stops at Home: once more would close the Tool.
        for _ in range(3):
            if has_label("Chess") and has_label("About"):
                break
            focused()
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
            time.sleep(0.8)
        scroll_to("About")  # in view at once since N11
        tap_label("About")
        wait_for(r"^Chess \d")
        # About scrolls, and v3's privacy line pushes the Puzzles line below the fold: scroll by touch
        # until nothing new shows, keeping each line once, in order. The screenshot after is the end.
        seen = []
        for _ in range(12):
            new = [t for t in texts() if t not in seen]
            if not new and seen:
                break
            seen += new
            focused()
            adb("shell", "input", "swipe", "540", "1000", "540", "300", "200")
            time.sleep(0.6)
        print("\n".join(seen))
    elif cmd == "shot":
        with open(argv[1], "wb") as f:
            f.write(adb("exec-out", "screencap", "-p", binary=True))
    else:
        sys.exit(__doc__)


main(sys.argv[1:])
