# Charcoal (charcoal)

Files: `icon.svg` (24x24, single colour #000000, strokes 2.04 = 0.085 x 24 like `ToolIconView`), `stamp.png`, `preview.png` (512x128, ink #272320 on transparent).
`stamp.png`: grayscale+alpha (PNG mode LA), L=255 everywhere, **alpha = coverage**. Tileable coarse grain; use as a texture layer multiplying stroke alpha.

## Stroke behaviour
- **Pressure -> width:** 0.7x -> 1.2x (stick pressed flat = wider mark).
- **Pressure -> opacity:** per-stamp 0.12 -> 0.30, accumulating; heavy pressure fills the paper tooth.
- **Velocity:** none (optional: fast = slightly lighter).
- **Taper:** soft crumbly start (0.5 -> 1x over ~0.04 of a stroke), abrupt end. No pointed exit.
- **Spacing:** 0.12 x diameter. **Jitter:** position +-8%, plus a second low-frequency 'rough edge' mask (+-20% coverage).
- **Grain:** `stamp.png` = **tileable coarse grain, 256x256, ~49% coverage**, three octaves (coarser and sparser than the pencil grain). World-anchored, 1 texel ~ 0.5 dp.
- **Edge:** ragged (0.22 softness x rough mask). **Blend:** source-over; optionally multiply so layers darken like real charcoal.

## Config dialog (same ToolboxPopover as Pen/Brush)
Header title + 110x34dp live stroke pill, **Ink color** (8 `INK_SWATCHES` + Custom HSV), **Size** (value text, 3 presets, SeekBar 0..100 mapped to min..max).
- Size: min 6 dp, max 30 dp, default 13 dp, presets 8 / 13 / 22 dp (wide side of the stick).
- Default ink: sumi `0xff272320`.
- Optional extra SeekBar row: **Grain**, 0..100, default 60 = grain contrast / hole size.
