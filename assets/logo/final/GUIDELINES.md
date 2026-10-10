# LexiRead — logo guidelines

The approved direction is **Bookmark Right**: Openlib's book-and-bookmark reading,
mirrored and recoloured into LexiRead's own palette. One slab, one ribbon, one ground.

---

## 1. The mark

```
viewBox 0 0 256 256

  slab     x 78..178   y 65..191   rx 13      100 x 126 units
  ribbon   x 143..165  y 65..131               22 x 66 units
           tail notch 19 deep, 11 half-width   (60 deg)
  ground   x 16..240   y 16..240   rx 50       224 x 224 units  (app icon only)
```

Three facts about the construction are deliberate:

- **The ribbon's right edge sits on the slab's corner tangent.** The slab's flat top
  edge runs from x 91 to x 165 and the ribbon ends at x 165, so the 13 units of navy
  to its right are exactly the corner arc. Moving the ribbon 1 unit right breaks it.
- **The ribbon is flush with the slab's top edge.** This is what lets the mono cut
  (§5) read as an open notch rather than a closed hole.
- **Nothing is smaller than 1/12 of the mark.** The ribbon is 22 units against a
  126-unit height, which is why it survives 48 px.

## 2. Colour

| Name | HEX | RGB | Use |
|---|---|---|---|
| Navy 900 | `#0F172A` | 15 23 42 | The slab; the primary ink |
| Gold 500 | `#D4AC54` | 212 172 84 | The ribbon; the accent |
| Cream 500 | `#F6F2EB` | 246 242 235 | The ground |

Measured contrast:

- navy on cream — **16.0 : 1**. Excellent; this carries the mark.
- gold on navy — **8.4 : 1**. Strong; this is why the ribbon reads.
- gold on cream — **1.9 : 1**. **Weak.** Never put the ribbon directly on the cream
  ground — the ribbon only ever sits on the slab. If you need the mark on a light
  surface, use the navy slab as drawn.

The mark is two colours. There is no version that keeps both elements in one ink
without the mono cut in §5.

## 3. Grounds

- **Light surfaces** — the symbol as drawn (navy slab, gold ribbon).
- **Dark surfaces** — the reversed symbol: cream slab, gold ribbon. Keeps the
  two-tone reading instead of dissolving into the background.
- **App icon** — the cream squircle. The cream ground is the point: it is what makes
  the icon findable on a crowded home screen, and it echoes the paper the app is about.

## 4. Clear space and minimum sizes

**Clear space** — keep a margin of **25 units** on the 256 grid (a quarter of the
slab's width) clear on all four sides. Nothing — type, rules, other logos, the edge
of the surface — enters that band.

**Minimum sizes**

| Context | Minimum | Use |
|---|---|---|
| Launcher icon | 48 px | the supplied `mipmap-mdpi` raster |
| Digital, standard mark | 24 px | `lexiread-symbol.svg` |
| Digital, small sizes | 16 px | `lexiread-symbol-small.svg` (wider ribbon) |
| Print | 8 mm mark height | `lexiread-symbol.svg` |

Below 32 px the standard mark's ribbon greys out; that is what the small cut is for.

## 5. One colour, and Android themed icons

The ribbon sits *inside* the slab, so flattening the mark to one ink loses it. Use
**`lexiread-symbol-mono.svg`** instead: the slab is solid and the ribbon is punched
out with `fill-rule="evenodd"`. Because the ribbon is flush with the slab's top edge,
the cut-out reads as an open notch rather than a closed hole.

This same artwork is `drawable/ic_launcher_monochrome.xml`, which is what Android 13+
uses when the user turns on themed icons.

## 6. Misuse

- Do not move the ribbon off the slab's top edge, or to the left.
- Do not add the wordmark inside the icon — it is illegible at 48 px.
- Do not put the gold ribbon on the cream ground (1.9 : 1).
- Do not rotate, skew, outline or add shadows, gradients or glows.
- Do not re-colour outside the three colours above.
- Do not stretch: the slab is always 100 : 126.
- Do not add a second element. The mark is one idea.

## 7. Files

Everything is generated from `assets/logo/`; edit the scripts, not the SVGs.

| File | What it is |
|---|---|
| `final/lexiread-symbol.svg` | Symbol master, transparent ground |
| `final/lexiread-app-icon.svg` | Squircle tile — source for the mipmap rasters |
| `final/lexiread-app-icon-round.svg` | Circular tile — source for `ic_launcher_round` |
| `final/lexiread-app-icon-dark.svg` | Dark ground, for dark store surfaces |
| `final/lexiread-symbol-mono.svg` | One colour; ribbon punched out |
| `final/lexiread-symbol-reversed.svg` | For dark grounds |
| `final/lexiread-symbol-small.svg` | 16–24 px cut, wider ribbon |
| `final/lexiread-wordmark.svg` | Constructed wordmark |
| `final/lexiread-lockup-horizontal.svg` | Primary lockup |
| `final/lexiread-lockup-stacked.svg` | Where the width is constrained |
| `final/preview.html` | Full test sheet (sizes, backgrounds, treatments, contexts) |
| `final/presentation.html` | Presentation board with mockups |
| `export_android_icons.py` | Regenerates the five mipmap densities |
| `build_wordmark.py` | Regenerates the wordmark and lockups |

Shipped into the app:

| Resource | Role |
|---|---|
| `drawable/ic_launcher_background.xml` | Cream ground, 108 dp full bleed |
| `drawable/ic_launcher_foreground.xml` | Mark, scaled 0.4x, inside the 66 dp safe circle |
| `drawable/ic_launcher_monochrome.xml` | Themed-icon layer |
| `mipmap-anydpi-v26/ic_launcher{,_round}.xml` | Adaptive icon wiring |
| `mipmap-{mdpi…xxxhdpi}/ic_launcher{,_round}.webp` | Legacy rasters, 48/72/96/144/192 px |

## 8. Open items

- **Trademark.** This is a deliberate variation on Openlib's mark, not an original
  design. It differs in ribbon side, palette and ground, but it is close enough that
  a professional trademark search is worth running before publishing.
- **The wordmark is a neutral geometric grotesque**, built rather than set in a font,
  so there is no font licence to clear. It supports the symbol; it is not the
  distinctive asset.
- **The cream ground is a change** from the app's previous navy tile. Worth a look on
  a real device against the navy app chrome.
