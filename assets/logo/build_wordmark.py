#!/usr/bin/env python3
"""
Build the LexiRead wordmark and the two lockups, as SVG.

The letterforms are constructed, not set in a font: a monoline geometric
grotesque on a 100-unit cap height with a 14-unit stroke. Straight letters are
polygons; R and D carry true circular bowls. Everything is computed here so the
geometry is exact and reproducible rather than hand-typed.

Two things are deliberate and easy to break:

* Winding. Every subpath is emitted clockwise on screen (SVG y grows downwards),
  except the counters of A, R and D, which run counter-clockwise so that
  fill-rule="nonzero" punches them out. The R leg deliberately overlaps the stem
  and the bowl — with nonzero winding the overlap stays solid instead of opening
  a seam.
* The R leg's top edge sits at y=52 so the leg starts inside the bowl. Start it
  lower and the leg's left edge crosses the stem exactly as the bowl's outer arc
  bottoms out, which leaves a sub-unit sliver that reads as a notch.

Run:  python assets/logo/build_wordmark.py
"""
from __future__ import annotations

import re
from pathlib import Path

H = 100.0   # cap height
S = 14.0    # monoline stroke
GAP = 26.0  # letter spacing

NAVY = "#0F172A"
GOLD = "#D4AC54"
CREAM = "#F6F2EB"

OUT = Path(__file__).resolve().parent / "final"

# Argument counts for the absolute path commands the generators emit.
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "A": 7, "Z": 0}

# The generators write commands without a space after the letter ("M0 0 H14"),
# so tokenise on the letter/number boundary rather than on whitespace.
TOKEN_RE = re.compile(r"[A-Za-z]|-?\d*\.?\d+")


def n(v: float) -> str:
    """Format a number: at most 2 decimals, no trailing zeros."""
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s not in ("", "-") else "0"


def diag_width(w: float) -> float:
    """Horizontal cross-section of a monoline bar running (0,0)->(w,H).

    A bar of perpendicular thickness S tilted at angle theta from the horizontal
    is S/sin(theta) wide when cut horizontally, and sin(theta) = H/len.
    """
    return S * (w * w + H * H) ** 0.5 / H


# --------------------------------------------------------------------------- #
# Letterforms. Each returns (advance width, path data) with the origin at the
# cap-top left corner: x 0..w, y 0 (cap line) .. 100 (baseline).
# --------------------------------------------------------------------------- #

def letter_L() -> tuple[float, str]:
    w = 60.0
    return w, f"M0 0 H{n(S)} V{n(H - S)} H{n(w)} V{n(H)} H0 Z"


def letter_E() -> tuple[float, str]:
    w = 60.0
    mid_top, mid_bot, mid_right = 42.0, 56.0, 54.0
    return w, (f"M0 0 H{n(w)} V{n(S)} H{n(S)} V{n(mid_top)} H{n(mid_right)} "
               f"V{n(mid_bot)} H{n(S)} V{n(H - S)} H{n(w)} V{n(H)} H0 Z")


def letter_X() -> tuple[float, str]:
    w = 62.0
    t = diag_width(w)
    bar1 = f"M0 0 L{n(t)} 0 L{n(w)} {n(H)} L{n(w - t)} {n(H)} Z"
    bar2 = f"M{n(w - t)} 0 L{n(w)} 0 L{n(t)} {n(H)} L0 {n(H)} Z"
    return w, f"{bar1} {bar2}"


def letter_I() -> tuple[float, str]:
    return S, f"M0 0 H{n(S)} V{n(H)} H0 Z"


def letter_A() -> tuple[float, str]:
    """Pointed apex, real triangular counter, and a crossbar.

    Without the crossbar a monoline triangle-with-counter reads as a lambda, so
    the bar is not optional. It is drawn as a trapezoid whose sides follow the
    legs' outer edges, which makes it merge into them instead of growing little
    ears where a plain rectangle would overhang.
    """
    w = 72.0
    half = w / 2
    hoff = diag_width(half)        # horizontal cross-section of one leg
    slope = half / H               # |dx/dy| along a leg
    apex_y = hoff / slope          # where the two inner edges meet
    bl = (half + hoff) - slope * H
    br = (half - hoff) + slope * H
    cb_top, cb_bot = 62.0, 76.0
    outer = f"M{n(half)} 0 L{n(w)} {n(H)} H0 Z"
    counter = f"M{n(half)} {n(apex_y)} L{n(bl)} {n(H)} H{n(br)} Z"
    bar = (f"M{n(half - slope * cb_top)} {n(cb_top)} H{n(half + slope * cb_top)} "
           f"L{n(half + slope * cb_bot)} {n(cb_bot)} H{n(half - slope * cb_bot)} Z")
    return w, f"{outer} {counter} {bar}"


def letter_R() -> tuple[float, str]:
    w = 62.0
    r_out = 33.0                   # bowl outer radius -> the bowl ends at y 66
    r_in = r_out - S               # counter radius
    leg_top_y = 52.0               # inside the bowl; see the module docstring
    leg_right = w                  # the leg's bottom-right corner lands on w
    dx_shift = 37.0                # horizontal run of the leg's slant
    dy = H - leg_top_y
    length = (dx_shift * dx_shift + dy * dy) ** 0.5
    leg_w = S * length / dy        # horizontal width giving a 14-unit stroke
    x_tl = leg_right - dx_shift - leg_w

    stem_bowl = (f"M0 0 H{n(S)} A{n(r_out)} {n(r_out)} 0 0 1 {n(S)} {n(2 * r_out)} "
                 f"V{n(H)} H0 Z")
    counter = (f"M{n(S)} {n(r_out + r_in)} "
               f"A{n(r_in)} {n(r_in)} 0 0 0 {n(S)} {n(r_out - r_in)} Z")
    leg = (f"M{n(x_tl)} {n(leg_top_y)} H{n(x_tl + leg_w)} "
           f"L{n(leg_right)} {n(H)} H{n(leg_right - leg_w)} Z")
    return w, f"{stem_bowl} {counter} {leg}"


def letter_D() -> tuple[float, str]:
    w = 64.0
    r_out = H / 2                  # the bowl spans the full cap height
    r_in = r_out - S
    outer = f"M0 0 H{n(S)} A{n(r_out)} {n(r_out)} 0 0 1 {n(S)} {n(H)} H0 Z"
    counter = (f"M{n(S)} {n(H - S)} "
               f"A{n(r_in)} {n(r_in)} 0 0 0 {n(S)} {n(S)} Z")
    return w, f"{outer} {counter}"


LETTERS = {"L": letter_L, "E": letter_E, "X": letter_X, "I": letter_I,
           "R": letter_R, "A": letter_A, "D": letter_D}

# Optical spacing. A uniform letter space leaves the narrow I floating, because
# its neighbours' sidebearings are large next to a 14-unit stem. Pull it in.
KERN = {("X", "I"): -6.0, ("I", "R"): -6.0}


def shift_x(path: str, dx: float) -> str:
    """Translate a path's x coordinates by dx.

    Only the x arguments move: for A that is the endpoint x (7th argument), not
    the radii, the x-axis-rotation or the two flags.
    """
    if dx == 0:
        return path
    tokens = TOKEN_RE.findall(path)
    out: list[str] = []
    i = 0
    cmd = "M"
    while i < len(tokens):
        token = tokens[i]
        if token[0].isalpha():
            cmd = token.upper()
            out.append(token)
            i += 1
            continue
        k = ARITY[cmd]
        args = [float(v) for v in tokens[i:i + k]]
        i += k
        if cmd in ("M", "L", "H"):
            args[0] += dx
        elif cmd == "A":
            args[5] += dx
        # V and Z carry nothing that moves horizontally.
        out.extend(n(a) for a in args)
    return " ".join(out)


def build_word(text: str) -> tuple[float, str]:
    """Lay the text out from x=0; return (total width, combined path data)."""
    parts: list[str] = []
    x = 0.0
    for i, ch in enumerate(text):
        if i:
            x += GAP + KERN.get((text[i - 1], ch), 0.0)
        w, d = LETTERS[ch]()
        parts.append(shift_x(d, x))
        x += w
    return x, " ".join(parts)


# The slab, lifted out of lexiread-symbol.svg (x 78..178, y 65..191) to the
# origin. Ribbon x 143..165 -> 65..87; y 65..131 -> 0..66; notch apex (154,112)
# -> (76,47).
SYM_W, SYM_H = 100.0, 126.0
SYM_BODY = (
    f'<rect x="0" y="0" width="{n(SYM_W)}" height="{n(SYM_H)}" rx="13" fill="{NAVY}"/>\n'
    f'  <path d="M65 0 H87 V66 L76 47 L65 66 Z" fill="{GOLD}"/>'
)

HEADER = ("  Generated by assets/logo/build_wordmark.py — edit the script, "
          "not this file.\n")


def write_svg(path: Path, comment: str, view_w: float, view_h: float, body: str) -> None:
    path.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f"<!--\n{comment}{HEADER}-->\n"
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {n(view_w)} {n(view_h)}" '
        f'width="{n(view_w)}" height="{n(view_h)}" role="img" aria-label="LexiRead">\n'
        f"  <title>LexiRead</title>\n"
        f"{body}"
        "</svg>\n",
        encoding="utf-8",
    )


def main() -> int:
    width, data = build_word("LEXIREAD")
    OUT.mkdir(parents=True, exist_ok=True)

    wordmark_path = f'  <path fill="{NAVY}" fill-rule="nonzero" d="{data}"/>'
    write_svg(
        OUT / "lexiread-wordmark.svg",
        "  LexiRead — wordmark.\n\n"
        "  Constructed letterforms, not a font: a monoline geometric grotesque on a\n"
        f"  {n(H)}-unit cap height, a {n(S)}-unit stroke and a {n(GAP)}-unit letter space.\n"
        "  A, R and D have real counters; the R leg overlaps its stem and bowl so no\n"
        "  seam opens where they meet.\n",
        width, H, wordmark_path,
    )

    # --- lockups ---------------------------------------------------------- #
    cap = 56.0
    scale = cap / H
    wm_w = width * scale
    mark_gap = 40.0

    # Horizontal: symbol left, wordmark right, wordmark centred on the mark's axis.
    total_w = SYM_W + mark_gap + wm_w
    write_svg(
        OUT / "lexiread-lockup-horizontal.svg",
        "  LexiRead — horizontal lockup. Symbol left, wordmark right.\n\n"
        f"  The wordmark's cap height is {n(cap)} against a {n(SYM_H)}-unit symbol and is\n"
        f"  centred on the symbol's optical axis; the gap between them is {n(mark_gap)} units.\n"
        "  Primary lockup — use wherever the width allows.\n",
        total_w, SYM_H,
        f'  <g>{SYM_BODY}</g>\n'
        f'  <g transform="translate({n(SYM_W + mark_gap)},{n((SYM_H - cap) / 2)}) '
        f'scale({n(scale)})">\n{wordmark_path}\n  </g>',
    )

    # Stacked: symbol above, wordmark below, both on the same centre line.
    total_w_s = max(SYM_W, wm_w)
    total_h = SYM_H + mark_gap + cap
    write_svg(
        OUT / "lexiread-lockup-stacked.svg",
        "  LexiRead — stacked lockup. Symbol above, wordmark below.\n\n"
        f"  Both centred on x, {n(mark_gap)} units apart vertically. Use where the width is\n"
        "  constrained: app splash, square social avatar, signage.\n",
        total_w_s, total_h,
        f'  <g transform="translate({n((total_w_s - SYM_W) / 2)},0)">{SYM_BODY}</g>\n'
        f'  <g transform="translate({n((total_w_s - wm_w) / 2)},{n(SYM_H + mark_gap)}) '
        f'scale({n(scale)})">\n{wordmark_path}\n  </g>',
    )

    print(f"wordmark {n(width)} x {n(H)} units; lockups written to {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
