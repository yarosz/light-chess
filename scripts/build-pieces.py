#!/usr/bin/env python3
"""Convert the piece SVGs into Kotlin ImageVector code (decision log R1.7, P1, P2).

    scripts/build-pieces.py               write tool/src/main/kotlin/com/yarosz/chess/board/PieceVectors.kt
    scripts/build-pieces.py --check       run the self-test, then exit 1 if the committed file differs
                                          from what the SVGs give
    scripts/build-pieces.py --self-test   check the converter on small inputs

Input: art/pieces/<set>/<w|b><K|Q|R|B|N|P>.svg, one directory per Piece Set (see its README).
Output: one generated Kotlin file that builds each piece of each set with ImageVector's path builder
(moveTo / lineTo / curveTo / close), so the Tool parses no SVG or path text at run time and Light's
offline builder needs nothing extra.

The conversion flattens each SVG: transforms are applied to the points, circles, elliptical arcs and
quadratic curves become cubic Béziers, relative and shorthand commands become absolute M/L/C/Z, and
style is resolved through the <g> inheritance chain (attributes and `style=""`). Paths keep the
document's order, so a white piece's wide black outline is drawn before the white fill over it. Only
the SVG features the piece files use are supported; anything else, an attribute included, fails
loudly. Python standard library only; no network.

It also writes each set's captured-black twins (decision log P3): for every piece but the king, the
white drawing with its white body painted CAPTURED_BLACK_BODY gray, which the captured-pieces row
under the board draws for a captured black piece. The row's ground is black, where the solid black
drawing would vanish; the twin keeps the black outer line, so overlapping pieces stay separate.
"""

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "art" / "pieces"
# (Kotlin PieceSet name, directory under art/pieces). Order is the PieceSet enum's.
SETS = [("GEOMETRIC", "geometric"), ("ROUNDED", "rounded")]
OUT = ROOT / "tool" / "src" / "main" / "kotlin" / "com" / "yarosz" / "chess" / "board" / "PieceVectors.kt"
NS = "{http://www.w3.org/2000/svg}"

# The body of a captured black piece in the captured-pieces row (P3): `Shades.CAPTURED_BLACK_BODY`,
# which ShadesTest and PieceVectorsTest tie to this value.
CAPTURED_BLACK_BODY = 0x96
# The kinds a Side can capture: every piece but the king, in the Piece enum's order.
CAPTURABLE = [(name, letter) for name, letter in
              (("PAWN", "P"), ("KNIGHT", "N"), ("BISHOP", "B"), ("ROOK", "R"), ("QUEEN", "Q"))]

# (Kotlin Piece name, file letter). Order is the Piece enum's.
PIECES = [(side, name, letter) for side in ("WHITE", "BLACK") for name, letter in
          (("PAWN", "P"), ("KNIGHT", "N"), ("BISHOP", "B"), ("ROOK", "R"), ("QUEEN", "Q"), ("KING", "K"))]

INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule")
DEFAULTS = {"fill": "#000000", "stroke": "none", "stroke-width": "1", "stroke-linecap": "butt",
            "stroke-linejoin": "miter", "fill-rule": "nonzero"}
IGNORED = {"opacity": "1", "fill-opacity": "1", "stroke-opacity": "1", "stroke-miterlimit": "4",
           "stroke-dasharray": "none"}
# Geometry and structure, read where they apply rather than as style. An attribute in none of
# INHERITED, IGNORED and STRUCTURE (paint-order, display, visibility, stroke-dashoffset, ...) fails,
# so a presentation attribute is never dropped without notice.
STRUCTURE = {"d", "cx", "cy", "r", "transform", "viewBox", "width", "height", "version", "id", "style"}

IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)  # SVG matrix(a b c d e f)


def mul(m, n):
    a, b, c, d, e, f = m
    A, B, C, D, E, F = n
    return (a * A + c * B, b * A + d * B, a * C + c * D, b * C + d * D, a * E + c * F + e, b * E + d * F + f)


def apply(m, x, y):
    a, b, c, d, e, f = m
    return (a * x + c * y + e, b * x + d * y + f)


def parse_transform(text):
    m = IDENTITY
    for kind, args in re.findall(r"(\w+)\s*\(([^)]*)\)", text or ""):
        v = [float(x) for x in re.split(r"[\s,]+", args.strip())]
        if kind == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0.0)
        elif kind == "matrix":
            t = tuple(v)
        else:
            raise SystemExit(f"unsupported transform {kind}")
        m = mul(m, t)
    return m


def style_of(el, parent):
    """The element's style over its parent's. Unknown attributes and declarations fail (STRUCTURE)."""
    s = dict(parent)
    props = [(k, v, False) for k, v in el.attrib.items()]
    for decl in (el.get("style") or "").split(";"):
        if decl.strip():
            if ":" not in decl:
                raise SystemExit(f"unsupported style declaration {decl.strip()!r}")
            k, v = decl.split(":", 1)
            props.append((k.strip(), v.strip(), True))
    for k, v, in_style in props:
        if k in INHERITED:
            s[k] = v
        elif k in IGNORED:
            if v != IGNORED[k]:
                raise SystemExit(f"unsupported {k}={v}")
        elif k not in STRUCTURE or in_style:
            raise SystemExit(f"unsupported attribute {k}={v!r}")
    return s


def color(v):
    v = v.lower()
    if v == "none":
        return None
    if re.fullmatch(r"#[0-9a-f]{3}", v):
        v = "#" + "".join(ch * 2 for ch in v[1:])
    if not re.fullmatch(r"#[0-9a-f]{6}", v):
        raise SystemExit(f"unsupported colour {v}")
    return v[1:].upper()


TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def tokens(d):
    return TOKEN.findall(d)


def arc_to_cubics(x1, y1, rx, ry, phi_deg, large, sweep, x2, y2):
    """SVG arc (endpoint form) to cubic Béziers, per the SVG spec's appendix F.6."""
    if (x1, y1) == (x2, y2):
        return []
    rx, ry = abs(rx), abs(ry)
    if rx == 0 or ry == 0:
        return [((x1, y1), (x2, y2), (x2, y2))]
    phi = math.radians(phi_deg)
    cp, sp = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    lam = (x1p ** 2) / (rx ** 2) + (y1p ** 2) / (ry ** 2)
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx ** 2 * ry ** 2 - rx ** 2 * y1p ** 2 - ry ** 2 * x1p ** 2
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = math.sqrt(max(0.0, num / den))
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cp * cxp - sp * cyp + (x1 + x2) / 2
    cy = sp * cxp + cp * cyp + (y1 + y2) / 2

    def angle(ux, uy, vx, vy):
        a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        return a

    t1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    step = dt / n
    k = 4 / 3 * math.tan(step / 4)
    out = []

    def point(t):
        x, y = rx * math.cos(t), ry * math.sin(t)
        return (cp * x - sp * y + cx, sp * x + cp * y + cy)

    def deriv(t):
        x, y = -rx * math.sin(t), ry * math.cos(t)
        return (cp * x - sp * y, sp * x + cp * y)

    t = t1
    for i in range(n):
        p0, p3 = point(t), point(t + step)
        d0, d3 = deriv(t), deriv(t + step)
        c1 = (p0[0] + k * d0[0], p0[1] + k * d0[1])
        c2 = (p3[0] - k * d3[0], p3[1] - k * d3[1])
        if i == n - 1:
            p3 = (x2, y2)
        out.append((c1, c2, p3))
        t += step
    return out


def path_ops(d):
    """Path data -> absolute ops: ('M', p), ('L', p), ('C', c1, c2, p), ('Z',)."""
    toks = tokens(d)
    i = 0
    ops = []
    cur = (0.0, 0.0)
    start = (0.0, 0.0)
    last_ctrl = None
    cmd = None

    def num():
        nonlocal i
        if i >= len(toks) or re.fullmatch(r"[A-Za-z]", toks[i]):
            raise SystemExit(f"path data: {cmd} is missing a number")
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        if re.fullmatch(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
        elif cmd is None:
            raise SystemExit("path data: a number with no command (at the start, or after Z)")
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == "Z":
            ops.append(("Z",))
            cur = start
            last_ctrl = None
            cmd = None  # Z takes no numbers: a number after it is an error, not another Z
            continue
        if c == "M":
            p = (ox + num(), oy + num())
            ops.append(("M", p))
            cur = start = p
            cmd = "l" if rel else "L"  # later pairs are implicit lineto
            last_ctrl = None
        elif c == "L":
            p = (ox + num(), oy + num())
            ops.append(("L", p))
            cur = p
            last_ctrl = None
        elif c == "H":
            p = ((ox if rel else 0) + num(), cur[1])
            ops.append(("L", p))
            cur = p
            last_ctrl = None
        elif c == "V":
            p = (cur[0], (oy if rel else 0) + num())
            ops.append(("L", p))
            cur = p
            last_ctrl = None
        elif c == "C":
            c1 = (ox + num(), oy + num())
            c2 = (ox + num(), oy + num())
            p = (ox + num(), oy + num())
            ops.append(("C", c1, c2, p))
            cur, last_ctrl = p, c2
        elif c == "S":
            c1 = (2 * cur[0] - last_ctrl[0], 2 * cur[1] - last_ctrl[1]) if last_ctrl else cur
            c2 = (ox + num(), oy + num())
            p = (ox + num(), oy + num())
            ops.append(("C", c1, c2, p))
            cur, last_ctrl = p, c2
        elif c == "Q":
            q = (ox + num(), oy + num())
            p = (ox + num(), oy + num())
            c1 = (cur[0] + 2 / 3 * (q[0] - cur[0]), cur[1] + 2 / 3 * (q[1] - cur[1]))
            c2 = (p[0] + 2 / 3 * (q[0] - p[0]), p[1] + 2 / 3 * (q[1] - p[1]))
            ops.append(("C", c1, c2, p))
            cur = p
            last_ctrl = None  # S reflects only a C or S control point
        elif c == "A":
            rx, ry, rot = num(), num(), num()
            large, sweep = num() != 0, num() != 0
            p = (ox + num(), oy + num())
            for c1, c2, q in arc_to_cubics(cur[0], cur[1], rx, ry, rot, large, sweep, p[0], p[1]):
                ops.append(("C", c1, c2, q))
            cur = p
            last_ctrl = None
        else:
            raise SystemExit(f"unsupported path command {cmd}")
    return ops


def circle_ops(cx, cy, r):
    k = 0.5522847498 * r
    return [
        ("M", (cx + r, cy)),
        ("C", (cx + r, cy + k), (cx + k, cy + r), (cx, cy + r)),
        ("C", (cx - k, cy + r), (cx - r, cy + k), (cx - r, cy)),
        ("C", (cx - r, cy - k), (cx - k, cy - r), (cx, cy - r)),
        ("C", (cx + k, cy - r), (cx + r, cy - k), (cx + r, cy)),
        ("Z",),
    ]


def walk(el, style, matrix, out):
    tag = el.tag.replace(NS, "")
    style = style_of(el, style)
    matrix = mul(matrix, parse_transform(el.get("transform")))
    if tag in ("svg", "g"):
        for child in el:
            walk(child, style, matrix, out)
        return
    if tag == "path":
        ops = path_ops(el.get("d"))
    elif tag == "circle":
        ops = circle_ops(float(el.get("cx")), float(el.get("cy")), float(el.get("r")))
    else:
        raise SystemExit(f"unsupported element {tag}")
    a, b, c, d, _, _ = matrix
    scale = math.sqrt(abs(a * d - b * c))
    moved = []
    for op in ops:
        moved.append((op[0],) + tuple(apply(matrix, *p) for p in op[1:]))
    out.append({
        "fill": color(style["fill"]),
        "stroke": color(style["stroke"]),
        "width": float(style["stroke-width"]) * scale,
        "cap": style["stroke-linecap"],
        "join": style["stroke-linejoin"],
        "evenodd": style["fill-rule"] == "evenodd",
        "ops": moved,
    })


def fmt(v):
    s = f"{v:.3f}".rstrip("0").rstrip(".")
    if s in ("-0", ""):
        s = "0"
    return s + "f"


def recolour(p, white):
    """[p] with its white fill and stroke painted [white] (a six-digit hex colour) instead."""
    swap = lambda c: white if c == "FFFFFF" else c
    return dict(p, fill=swap(p["fill"]), stroke=swap(p["stroke"]))


def kotlin_path(p):
    args = [
        f"fill = {'SolidColor(Color(0xFF' + p['fill'] + '))' if p['fill'] else 'null'}",
        f"stroke = {'SolidColor(Color(0xFF' + p['stroke'] + '))' if p['stroke'] else 'null'}",
        f"strokeLineWidth = {fmt(p['width'])}",
        f"strokeLineCap = StrokeCap.{p['cap'].capitalize()}",
        f"strokeLineJoin = StrokeJoin.{p['join'].capitalize()}",
        f"pathFillType = PathFillType.{'EvenOdd' if p['evenodd'] else 'NonZero'}",
    ]
    lines = [f"        path({', '.join(args)}) {{"]
    for op in p["ops"]:
        pts = ", ".join(f"{fmt(x)}, {fmt(y)}" for x, y in op[1:])
        name = {"M": "moveTo", "L": "lineTo", "C": "curveTo", "Z": "close"}[op[0]]
        lines.append(f"            {name}({pts})")
    lines.append("        }")
    return "\n".join(lines)


def fun_name(set_name, side, name):
    return f"{set_name.lower()}{side.capitalize()}{name.capitalize()}"


def read_paths(directory, file):
    root = ET.parse(file).getroot()
    if root.get("viewBox") != "0 0 45 45" or root.get("width") not in (None, "45") or root.get("height") not in (None, "45"):
        raise SystemExit(f"{directory}/{file.name}: expected a 45x45 SVG (viewBox 0 0 45 45)")
    paths = []
    walk(root, dict(DEFAULTS), IDENTITY, paths)
    return paths


def captured_name(set_name, name):
    return f"{set_name.lower()}CapturedBlack{name.capitalize()}"


def generate():
    blocks = []
    grey = f"{CAPTURED_BLACK_BODY:02X}" * 3
    for set_name, directory in SETS:
        for side, name, letter in PIECES:
            file = SRC / directory / f"{side[0].lower()}{letter}.svg"
            body = "\n".join(kotlin_path(p) for p in read_paths(directory, file))
            blocks.append(
                f"    private fun {fun_name(set_name, side, name)}(): ImageVector = piece(\"{set_name}_{side}_{name}\") {{\n"
                f"        // {directory}/{file.name}\n{body}\n    }}\n"
            )
        for name, letter in CAPTURABLE:
            file = SRC / directory / f"w{letter}.svg"
            body = "\n".join(kotlin_path(recolour(p, grey)) for p in read_paths(directory, file))
            blocks.append(
                f"    private fun {captured_name(set_name, name)}(): ImageVector = piece(\"{set_name}_CAPTURED_BLACK_{name}\") {{\n"
                f"        // {directory}/{file.name}, its white body painted gray\n{body}\n    }}\n"
            )
    whens = "\n".join(
        f"        PieceSet.{set_name} -> when (piece) {{\n"
        + "\n".join(f"            Piece.{s}_{n} -> {fun_name(set_name, s, n)}()" for s, n, _ in PIECES)
        + "\n        }"
        for set_name, _ in SETS
    )
    captured_whens = "\n".join(
        f"        PieceSet.{set_name} -> when (type) {{\n"
        + "\n".join(f"            PieceType.{n} -> {captured_name(set_name, n)}()" for n, _ in CAPTURABLE)
        + "\n            PieceType.KING -> throw IllegalArgumentException(\"a king is never captured\")"
        + "\n        }"
        for set_name, _ in SETS
    )
    return f"""// GENERATED by scripts/build-pieces.py from art/pieces (original drawings, CC0 1.0).
// Do not edit: change the SVGs or the script and rerun it.
@file:Suppress("MagicNumber")

package com.yarosz.chess.board

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.yarosz.chess.rules.Piece
import com.yarosz.chess.rules.PieceType
import com.yarosz.chess.rules.Side

/**
 * Both Piece Sets (P1, P2) as ImageVectors on a 45 × 45 viewport, each piece built once on first use,
 * and each set's captured-black twins for the captured-pieces row (P3).
 */
internal object PieceVectors {{

    private val cache = arrayOfNulls<ImageVector>(PieceSet.entries.size * Piece.entries.size)
    private val capturedCache = arrayOfNulls<ImageVector>(PieceSet.entries.size * PieceType.entries.size)

    fun vector(set: PieceSet, piece: Piece): ImageVector {{
        val i = set.ordinal * Piece.entries.size + piece.ordinal
        return cache[i] ?: build(set, piece).also {{ cache[i] = it }}
    }}

    /**
     * [piece] as the captured-pieces row draws it (P3): a white piece as on the board; a black one as
     * the white drawing with its body gray, so it keeps an outer line on the black ground.
     */
    fun captured(set: PieceSet, piece: Piece): ImageVector {{
        if (piece.side == Side.WHITE) return vector(set, piece)
        val i = set.ordinal * PieceType.entries.size + piece.type.ordinal
        return capturedCache[i] ?: buildCaptured(set, piece.type).also {{ capturedCache[i] = it }}
    }}

    private fun build(set: PieceSet, piece: Piece): ImageVector = when (set) {{
{whens}
    }}

    private fun buildCaptured(set: PieceSet, type: PieceType): ImageVector = when (set) {{
{captured_whens}
    }}

    private inline fun piece(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 45.dp, defaultHeight = 45.dp, viewportWidth = 45f, viewportHeight = 45f)
            .apply(paths)
            .build()

{chr(10).join(blocks)}}}
"""


def self_test():
    """The converter on small inputs: path data, the fail-loudly rules and style resolution."""
    import signal

    def fails(what, f, *args):
        try:
            f(*args)
        except SystemExit:
            return
        raise AssertionError(f"{what}: expected a failure")

    def close(a, b):
        return all(abs(x - y) < 1e-9 for x, y in zip(a, b))

    def el(**attrib):
        return ET.Element("path", {k.replace("_", "-"): v for k, v in attrib.items()})

    # A hang is a failure too: the Z bug below used to loop forever.
    if hasattr(signal, "alarm"):
        signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(AssertionError("self-test hung")))
        signal.alarm(10)

    ops = path_ops("M1 2 3 4l1 1H10v2Z")
    assert [op[0] for op in ops] == ["M", "L", "L", "L", "L", "Z"], ops
    assert close(ops[1][1], (3, 4)) and close(ops[2][1], (4, 5)), "implicit and relative lineto"
    assert close(ops[3][1], (10, 5)) and close(ops[4][1], (10, 7)), "H and V"
    ops = path_ops("M0 0Q3 3 6 0")
    assert ops[1][0] == "C" and close(ops[1][1], (2, 2)) and close(ops[1][2], (4, 2)) and close(ops[1][3], (6, 0)), ops
    ops = path_ops("M0 0A5 5 0 0 1 10 0")
    assert all(op[0] == "C" for op in ops[1:]) and close(ops[-1][3], (10, 0)), "an arc ends on its endpoint"
    ops = path_ops("M0 0L1 1ZM2 2L3 3z")
    assert [op[0] for op in ops] == ["M", "L", "Z", "M", "L", "Z"], ops
    fails("numbers after Z", path_ops, "M0 0L1 1Z 5 5")
    fails("a number first", path_ops, "5 5")
    fails("too few numbers", path_ops, "M0 0L1")
    fails("a letter for a number", path_ops, "M0 0LZ")
    fails("an unsupported command", path_ops, "M0 0T1 1")

    s = style_of(el(fill="#fff", stroke="#000", stroke_width="2", opacity="1", d="M0 0"), DEFAULTS)
    assert (s["fill"], s["stroke"], s["stroke-width"]) == ("#fff", "#000", "2"), s
    s = style_of(el(style="fill: #123; stroke-linejoin: round"), DEFAULTS)
    assert (s["fill"], s["stroke-linejoin"]) == ("#123", "round"), s
    for attr in ("paint-order", "display", "visibility", "stroke-dashoffset", "clip-path", "mask", "filter"):
        fails(f"attribute {attr}", style_of, ET.Element("path", {attr: "x"}), DEFAULTS)
        fails(f"style {attr}", style_of, el(style=f"{attr}: x"), DEFAULTS)
    fails("opacity other than 1", style_of, el(opacity="0.5"), DEFAULTS)
    fails("geometry inside style", style_of, el(style="d: path('M0 0')"), DEFAULTS)
    fails("a colour it can't read", color, "red")
    assert color("#Fa0") == "FFAA00" and color("none") is None
    # P3's twins: only white turns gray; black lines and cuts, and no paint, stay as they are.
    p = recolour({"fill": "FFFFFF", "stroke": "000000", "width": 1.0}, "969696")
    assert (p["fill"], p["stroke"], p["width"]) == ("969696", "000000", 1.0), p
    p = recolour({"fill": None, "stroke": "FFFFFF"}, "969696")
    assert (p["fill"], p["stroke"]) == (None, "969696"), p

    if hasattr(signal, "alarm"):
        signal.alarm(0)
    print("build-pieces: self-test passed")


def main():
    args = sys.argv[1:]
    if "--self-test" in args or "--check" in args:
        self_test()
        if "--self-test" in args and "--check" not in args:
            return
    text = generate()
    if "--check" in args:
        if not OUT.exists() or OUT.read_text() != text:
            print(f"build-pieces: {OUT.relative_to(ROOT)} is stale; run scripts/build-pieces.py", file=sys.stderr)
            sys.exit(1)
        print("build-pieces: up to date")
        return
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text)
    print(f"build-pieces: wrote {OUT.relative_to(ROOT)} ({len(text.splitlines())} lines)")


if __name__ == "__main__":
    main()
