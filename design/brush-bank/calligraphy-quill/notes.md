# Calligraphy quill (calligraphy-quill)

Files: `icon.svg` (24x24, single colour #000000, strokes 2.04 = 0.085 x 24 like `ToolIconView`), `stamp.png`, `preview.png` (512x128, ink #272320 on transparent).
`stamp.png`: grayscale+alpha (PNG mode LA), L=255 everywhere, **alpha = coverage**. Tip shape (chisel nib at 40 deg), not a grain.

## Stroke behaviour
- **Pressure -> width:** very mild: 0.88x -> 1.0x (a chisel nib doesn't swell). Thick/thin comes from **stroke direction vs. nib angle**, not pressure.
- **Pressure -> opacity:** 0.9 -> 1.0 (flat, opaque ink).
- **Velocity:** none. Slight ink running dry: -18% opacity over a long stroke (optional).
- **Taper:** none; flat chisel ends. Start/end width ramp 0.55x -> 1x over ~1 nib length only to avoid a hard dot.
- **Spacing:** 0.035 x nib length (stamp mode) / continuous mesh with elliptical tip (ink mode).
- **Jitter:** none (crisp). **Grain:** none; edges clean and hard (1 px AA).
- **Tip:** `stamp.png` is a 256x256 rounded-rectangle chisel nib, major:minor ~ 7:1, rotated 40 deg CCW from horizontal, fixed in screen space (do NOT rotate with stroke direction). In androidx.ink the same shape can be made without the PNG: `BrushTip(scaleX=1f, scaleY=0.14f, cornerRounding=0.2f, rotation=40deg)`.
- **Blend:** source-over, opaque.

## Config dialog (same ToolboxPopover as Pen/Brush)
Header title + 110x34dp live stroke pill, **Ink color** (8 `INK_SWATCHES` + Custom HSV), **Size** (value text, 3 presets, SeekBar 0..100 mapped to min..max).
- Size (nib length): min 4 dp, max 26 dp, default 11 dp, presets 6 / 11 / 18 dp.
- Default ink: Indigo `0xff36496e` (classic quill ink) - or keep `0xff272320`; initial value only.
- Optional extra SeekBar row: **Nib angle**, 0..90 deg, default 40, live in the preview pill (thick/thin contrast follows angle).
