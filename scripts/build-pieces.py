#!/usr/bin/env python3
"""Convert the vendored cburnett SVGs into Kotlin ImageVector code (decision log R1.7).

    scripts/build-pieces.py           write tool/src/main/kotlin/com/yarosz/chess/board/CburnettPieces.kt
    scripts/build-pieces.py --check   exit 1 if the committed file differs from what the SVGs give

Input: third_party/cburnett/Chess_<piece><l|d>t45.svg (see its README). Output: one generated Kotlin
file that builds each piece with ImageVector's path builder (moveTo / lineTo / curveTo / close), so
the Tool parses no SVG or path text at run time and Light's offline builder needs nothing extra.

The conversion flattens each SVG: transforms are applied to the points, circles and elliptical arcs
become cubic Béziers, relative and shorthand commands become absolute M/L/C/Z, and style is resolved
through the <g> inheritance chain (attributes and `style=""`). Only the SVG features these twelve
files use are supported; anything else fails loudly. Python standard library only; no network.
"""

import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "third_party" / "cburnett"
OUT = ROOT / "tool" / "src" / "main" / "kotlin" / "com" / "yarosz" / "chess" / "board" / "CburnettPieces.kt"
NS = "{http://www.w3.org/2000/svg}"

# (Kotlin Piece name, file letter). Order is the Piece enum's.
PIECES = [(side, name, letter) for side in ("WHITE", "BLACK") for name, letter in
          (("PAWN", "p"), ("KNIGHT", "n"), ("BISHOP", "b"), ("ROOK", "r"), ("QUEEN", "q"), ("KING", "k"))]

INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule")
DEFAULTS = {"fill": "#000000", "stroke": "none", "stroke-width": "1", "stroke-linecap": "butt",
            "stroke-linejoin": "miter", "fill-rule": "nonzero"}
IGNORED = {"opacity": "1", "fill-opacity": "1", "stroke-opacity": "1", "stroke-miterlimit": "4",
           "stroke-dasharray": "none"}

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
    s = dict(parent)
    props = {k: v for k, v in el.attrib.items()}
    for decl in (el.get("style") or "").split(";"):
        if ":" in decl:
            k, v = decl.split(":", 1)
            props[k.strip()] = v.strip()
    for k, v in props.items():
        if k in INHERITED:
            s[k] = v
        elif k in IGNORED:
            if v != IGNORED[k]:
                raise SystemExit(f"unsupported {k}={v}")
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
        v = float(toks[i])
        i += 1
        return v

    while i < len(toks):
        if re.fullmatch(r"[A-Za-z]", toks[i]):
            cmd = toks[i]
            i += 1
        elif cmd is None:
            raise SystemExit("path data starts with a number")
        rel = cmd.islower()
        c = cmd.upper()
        ox, oy = cur if rel else (0.0, 0.0)
        if c == "Z":
            ops.append(("Z",))
            cur = start
            last_ctrl = None
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


def generate():
    blocks = []
    for side, name, letter in PIECES:
        file = SRC / f"Chess_{letter}{'l' if side == 'WHITE' else 'd'}t45.svg"
        root = ET.parse(file).getroot()
        if (root.get("width"), root.get("height")) != ("45", "45"):
            raise SystemExit(f"{file.name}: expected a 45x45 SVG")
        paths = []
        walk(root, dict(DEFAULTS), IDENTITY, paths)
        body = "\n".join(kotlin_path(p) for p in paths)
        blocks.append(
            f"    private fun {side.lower()}{name.capitalize()}(): ImageVector = piece(\"{side}_{name}\") {{\n"
            f"        // {file.name}\n{body}\n    }}\n"
        )
    whens = "\n".join(f"        Piece.{s}_{n} -> {s.lower()}{n.capitalize()}()" for s, n, _ in PIECES)
    return f"""// GENERATED by scripts/build-pieces.py from third_party/cburnett (BSD-3-Clause, Colin M.L. Burnett).
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

/** The cburnett piece set as ImageVectors on a 45 × 45 viewport, built once per piece on first use. */
internal object CburnettPieces {{

    private val cache = arrayOfNulls<ImageVector>(Piece.entries.size)

    fun vector(piece: Piece): ImageVector =
        cache[piece.ordinal] ?: build(piece).also {{ cache[piece.ordinal] = it }}

    private fun build(piece: Piece): ImageVector = when (piece) {{
{whens}
    }}

    private inline fun piece(name: String, paths: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 45.dp, defaultHeight = 45.dp, viewportWidth = 45f, viewportHeight = 45f)
            .apply(paths)
            .build()

{chr(10).join(blocks)}}}
"""


def main():
    text = generate()
    if "--check" in sys.argv[1:]:
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
