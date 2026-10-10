#!/usr/bin/env python3
"""
Export the LexiRead launcher icons for Android from the final SVG masters.

Renders assets/logo/final/lexiread-app-icon.svg and -round.svg at each density
and writes them straight into app/src/main/res/mipmap-<density>/ as lossless
WebP, which is what Android expects for the legacy (API < 26) launcher icons.

Two things this does that a plain render loop does not:

* **Supersamples 4x, then downsamples with Lanczos.** The skill's renderer drives
  headless Chrome by screenshotting a page that holds the SVG in an <img>. At
  some viewport sizes it captures the frame before the image has painted and
  writes a fully transparent PNG — 96 and 100 px do this consistently on this
  machine, while 94, 95 and 192 are fine. Rendering at 4x and scaling down
  sidesteps the window-size dependency entirely and gives better edges on the
  flat shapes than a native 48 px render would.
* **Verifies every image is non-blank before it is written**, so a silent blank
  can never reach app/src/main/res again.

Run from anywhere:  python assets/logo/export_android_icons.py
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
RENDER_PNG = Path.home() / ".workbuddy-ai" / "skills" / "logo-design" / "scripts" / "render_png.py"
RES = ROOT / "app" / "src" / "main" / "res"
FINAL = ROOT / "assets" / "logo" / "final"

SUPERSAMPLE = 4
MIN_RENDER = 256

DENSITIES = [
    ("mdpi", 48),
    ("hdpi", 72),
    ("xhdpi", 96),
    ("xxhdpi", 144),
    ("xxxhdpi", 192),
]

# (source SVG, output file name inside each mipmap-<density> folder)
SOURCES = [
    ("lexiread-app-icon.svg", "ic_launcher.webp"),
    ("lexiread-app-icon-round.svg", "ic_launcher_round.webp"),
]


def render(svg: Path, size: int, tmp: Path) -> Image.Image:
    """Render svg at 4x and return a size x size RGBA image, or raise."""
    big = max(size * SUPERSAMPLE, MIN_RENDER)
    raw = tmp / f"{svg.stem}.{size}.png"
    subprocess.run(
        [sys.executable, str(RENDER_PNG), str(svg), "-o", str(raw), "--size", str(big)],
        check=True,
        stdout=subprocess.DEVNULL,
    )
    with Image.open(raw) as im:
        im.load()
        rgba = im.convert("RGBA")
        if rgba.getchannel("A").getextrema() == (0, 0):
            raise RuntimeError(f"{svg.name} rendered fully transparent at {big}px")
        return rgba.resize((size, size), Image.LANCZOS)


def main() -> int:
    if not RENDER_PNG.is_file():
        print(f"render_png.py not found at {RENDER_PNG}", file=sys.stderr)
        return 1

    tmp = Path(tempfile.mkdtemp(prefix="lexiread-icons-"))
    try:
        for svg_name, out_name in SOURCES:
            svg = FINAL / svg_name
            if not svg.is_file():
                print(f"missing source: {svg}", file=sys.stderr)
                return 1
            for density, size in DENSITIES:
                image = render(svg, size, tmp)
                target_dir = RES / f"mipmap-{density}"
                target_dir.mkdir(parents=True, exist_ok=True)
                target = target_dir / out_name
                image.save(target, format="WEBP", lossless=True)
                with Image.open(target) as check:
                    check.load()
                    alpha = check.convert("RGBA").getchannel("A").getextrema()
                flag = "ok" if alpha != (0, 0) else "BLANK"
                print(f"  {target.relative_to(ROOT)}  {size}x{size}  "
                      f"{target.stat().st_size:,} B  {flag}")
                if flag == "BLANK":
                    return 1
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
