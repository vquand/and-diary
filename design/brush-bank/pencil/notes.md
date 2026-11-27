# Pencil (pencil)

Files: `icon.svg` (24x24, single colour #000000, strokes 2.04 = 0.085 x 24 like `ToolIconView`), `stamp.png`, `preview.png` (512x128, ink #272320 on transparent).
`stamp.png`: grayscale+alpha (PNG mode LA), L=255 everywhere, **alpha = coverage**. Tileable grain (not a tip shape). Use as a texture layer multiplying stroke alpha.

## Stroke behaviour
- **Pressure -> width:** 0.35x at p=0 -> 1.0x at p=1 (linear). Pen-like but a little narrower at light touch.
- **Pressure -> opacity:** 0.45 at p=0 -> 0.95 at p=1 (graphite builds up with pressure; overlapping passes darken).
- **Velocity:** no effect on width; fast strokes lose ~10% opacity.
- **Taper:** short start ramp (0.5x brush size to full), end ramp ~1x brush size; never to a hairline point (graphite ends blunt-ish).
- **Spacing:** 0.12 x diameter (stamp mode) / continuous mesh (ink mode).
- **Jitter:** position +-6% of diameter, no rotation jitter.
- **Grain:** `stamp.png` is a **tileable paper-tooth grain** (256x256, ~56% mean coverage). World-anchored (does not move with the stroke), 1 texel = ~0.5 dp at default zoom. Light pressure shows more tooth; heavy pressure fills it (raise alpha floor).
- **Edge:** slightly rough, 0.35 softness. **Blend:** source-over, alpha accumulates on overlap.

## Config dialog (same ToolboxPopover as Pen/Brush)
Header title + 110x34dp live stroke pill, **Ink color** (8 `INK_SWATCHES` + Custom HSV), **Size** (value text, 3 presets, SeekBar 0..100 mapped to min..max).
- Size: min 1 dp, max 8 dp, default 2.5 dp, presets 1.5 / 2.5 / 4 dp (same feel as Pen, slightly finer top end).
- Default ink: graphite `0xff4a4743` (swatch row unchanged; this is only the initial value).
- Optional extra SeekBar row (same `sliderRow` style as Hue/Sat): **Softness** (HB<->6B), 0..100, default 40 = grain contrast / flow.
