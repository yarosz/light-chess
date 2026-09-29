#!/usr/bin/env python3
"""Generate Set 2 (rounded) chess piece SVGs.

Geometry is written once, in "core" coordinates. The black piece is the core
dilated by 1.7 (fill + 3.4 stroke, round joins), exactly like Set 1. The white
piece is the core scaled 0.9 about (22.5, 18.7) -- Set 1's transform, so the
outer line lands on the same 38.7 baseline -- drawn as a 7.06 black underlay and
a 3.06 white top, which leaves a 2-unit black line outside the silhouette.
"""
import re, sys, os

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "..")

S, CX, CY = 0.9, 22.5, 18.7
B_W, W_UNDER, W_TOP = 3.4, 7.06, 3.06
CUT_B = 2.4          # light cut on black pieces
CUT_W = 2.16         # dark cut on white pieces (0.9 x 2.4)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def tx(x): return CX + S * (x - CX)
def ty(y): return CY + S * (y - CY)


def scale_path(d, scale):
    """Rewrite an absolute-command path (M L H V C Q A Z)."""
    toks = re.findall(r"[MLHVCQAZ]|-?\d*\.?\d+", d)
    out, i, cmd = [], 0, None
    def num():
        nonlocal i
        v = float(toks[i]); i += 1; return v
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t; i += 1; out.append(cmd)
            if cmd == "Z":
                continue
        if cmd in "ML":
            x, y = num(), num(); out += [fmt(tx(x) if scale else x), fmt(ty(y) if scale else y)]
        elif cmd == "H":
            x = num(); out.append(fmt(tx(x) if scale else x))
        elif cmd == "V":
            y = num(); out.append(fmt(ty(y) if scale else y))
        elif cmd in "CQ":
            n = 3 if cmd == "C" else 2
            for _ in range(n):
                x, y = num(), num(); out += [fmt(tx(x) if scale else x), fmt(ty(y) if scale else y)]
        elif cmd == "A":
            rx, ry, rot, la, sw, x, y = [num() for _ in range(7)]
            k = S if scale else 1
            out += [fmt(rx * k), fmt(ry * k), fmt(rot), str(int(la)), str(int(sw)),
                    fmt(tx(x) if scale else x), fmt(ty(y) if scale else y)]
    # compact: command letters glued to first number
    s = ""
    for t in out:
        if t.isalpha():
            s += t
        else:
            s += ("" if (s and s[-1].isalpha()) else " ") + t
    return s.strip()


def circle(cx, cy, r):
    """Circle as a path (two arcs)."""
    f = fmt
    return f"M{f(cx - r)} {f(cy)}A{f(r)} {f(r)} 0 1 0 {f(cx + r)} {f(cy)}A{f(r)} {f(r)} 0 1 0 {f(cx - r)} {f(cy)}Z"


# ---------------------------------------------------------------------------
# Pieces. Each: body = list of ("fill", d) or ("line", d, width);
#             cuts = list of ("line", d) or ("dot", cx, cy, r)
# All core coordinates; baseline of core = 37.
# ---------------------------------------------------------------------------
PIECES = {}
exec(open(os.path.join(os.path.dirname(__file__), "shapes.py")).read(), {"PIECES": PIECES, "circle": circle})


def el_fill(d, fill, stroke_w):
    return (f'  <path d="{d}" fill="{fill}" stroke="{fill}" stroke-width="{fmt(stroke_w)}" '
            f'stroke-linejoin="round"/>')


def el_line(d, color, w):
    return (f'  <path d="{d}" fill="none" stroke="{color}" stroke-width="{fmt(w)}" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')


def el_dot(cx, cy, r, color):
    return f'  <path d="{circle(cx, cy, r)}" fill="{color}"/>'


def build(name, spec, white):
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 45 45">']
    body, cuts = spec["body"], spec.get("cuts", [])
    if not white:
        for e in body:
            if e[0] == "fill":
                lines.append(el_fill(scale_path(e[1], False), "#000", B_W))
            else:
                lines.append(el_line(scale_path(e[1], False), "#000", e[2] + B_W))
        for c in cuts:
            if c[0] == "line":
                lines.append(el_line(scale_path(c[1], False), "#fff", c[2] if len(c) > 2 else CUT_B))
            else:
                lines.append(el_dot(c[1], c[2], c[3], "#fff"))
    else:
        for layer, col, w in (("under", "#000", W_UNDER), ("top", "#fff", W_TOP)):
            for e in body:
                if e[0] == "fill":
                    lines.append(el_fill(scale_path(e[1], True), col, w))
                else:
                    lines.append(el_line(scale_path(e[1], True), col, S * e[2] + w))
        for c in cuts:
            if c[0] == "line":
                lines.append(el_line(scale_path(c[1], True), "#000",
                                     S * (c[2] if len(c) > 2 else CUT_B)))
            else:
                lines.append(el_dot(tx(c[1]), ty(c[2]), S * c[3], "#000"))
    lines.append("</svg>")
    return "\n".join(lines) + "\n"


for k, spec in PIECES.items():
    for side in "wb":
        with open(os.path.join(OUT, f"{side}{k}.svg"), "w") as f:
            f.write(build(k, spec, side == "w"))
print("wrote", len(PIECES) * 2, "svgs")
