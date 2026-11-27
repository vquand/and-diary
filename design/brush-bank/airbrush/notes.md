# Airbrush (airbrush)

Files: `icon.svg` (24x24, single colour #000000, strokes 2.04 = 0.085 x 24 like `ToolIconView`), `stamp.png`, `preview.png` (512x128, ink #272320 on transparent).
`stamp.png`: grayscale+alpha (PNG mode LA), L=255 everywhere, **alpha = coverage**. Soft radial tip with speckle (128 px). Not tileable.

## Stroke behaviour
- **Pressure -> width:** 0.55x -> 1.25x (pressure = distance from nozzle = wider spray).
- **Pressure -> opacity (flow):** per-stamp opacity 0.12 at p=0 -> 0.30 at p=1, accumulating.
- **Velocity -> opacity:** slow = denser (builds up while hovering/slow), fast = lighter (`0.5 + 0.8*(1-v)`).
- **Taper:** none (spray just starts/stops; the soft falloff is the taper).
- **Spacing:** 0.08 x diameter. **Jitter:** position +-4% plus ~1 splatter dot per 2 stamps (radius 0.7-1.6 px at 2x, placed 0.3-0.75 of radius out).
- **Grain:** none world-anchored; `stamp.png` (128x128) is a **soft round gaussian dot with faint speckle on the fringe**, NOT tileable (it is a tip).
- **Edge:** very soft (gaussian). **Blend:** source-over, accumulates; never fully opaque at default flow unless you linger.

## Config dialog (same ToolboxPopover as Pen/Brush)
Header title + 110x34dp live stroke pill, **Ink color** (8 `INK_SWATCHES` + Custom HSV), **Size** (value text, 3 presets, SeekBar 0..100 mapped to min..max).
- Size (spray radius): min 8 dp, max 48 dp, default 22 dp, presets 12 / 22 / 36 dp.
- Default ink: `0xff272320` (same as Brush).
- Optional extra SeekBar row: **Flow**, 0..100, default 40 = opacity per stamp (0.05..0.4).

## Cross-section gradient (`gradient.png`)
256x8 px, white RGB, alpha = coverage. Use it as a 1-D profile *across* the stroke width (u = 0 at the left edge, 0.5 at the centre line, 1 at the right edge; v is constant, so stretch or clamp the 8 px rows). Profile: Gaussian falloff (about σ ≈ 0.3 of the half-width) that reaches exactly 0 at both edges and 1.0 at the centre. Map it as a `BrushPaint` texture in stroke-space (or sample it by distance from the centre line in a custom shader) to get the soft airbrush body without radial stamping. Layer the 128 px `stamp.png` speckle dots on top sparsely if you want the overspray.
