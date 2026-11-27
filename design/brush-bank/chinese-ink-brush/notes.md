# Chinese ink brush (chinese-ink-brush)

Files: `icon.svg` (24x24, single colour #000000, strokes 2.04 = 0.085 x 24 like `ToolIconView`), `stamp.png`, `preview.png` (512x128, ink #272320 on transparent).
`stamp.png`: grayscale+alpha (PNG mode LA), L=255 everywhere, **alpha = coverage**. Directional bristle streaks; tileable in x and y; x = stroke direction.

## Stroke behaviour
- **Pressure -> width:** `0.12 + 0.88 * p^1.5` (strongly pressure-driven; hairline at light touch, full belly at press).
- **Pressure -> opacity:** 0.85 at p=0 -> 1.0 at p=1; extra pooling (+10% darker) when slow.
- **Velocity -> width:** fast = up to 40% thinner (`0.6 + 0.4*(1-v)`), and more dry-brush streaking.
- **Taper:** soft **entry** (0.55x -> 1x over ~1x size, slight round head) and a **long pointed exit**: `distance remaining` response 0 -> ~3x brush size reaching 0.08x (the existing Brush uses 1.2x size to 0.22x; this is longer and sharper).
- **Spacing:** 0.25 x radius (very smooth). **Jitter:** none on position; angle follows direction.
- **Grain:** `stamp.png` is a **bristle streak texture, 256x256, tileable both ways, streaks run along texture +x = stroke direction**; sample with stroke-aligned mapping (u = arclength, v = across stroke), ~1 texel = 0.35 dp along stroke. Coverage threshold rises as pressure drops / speed rises -> dry-brush gaps (alpha cut-off between 0.12 and 0.38).
- **Edge:** soft 0.35, slight bleed. **Blend:** source-over (ink looks semi-wet where strokes cross: optional 0.9 multiply).

## Config dialog (same ToolboxPopover as Pen/Brush)
Header title + 110x34dp live stroke pill, **Ink color** (8 `INK_SWATCHES` + Custom HSV), **Size** (value text, 3 presets, SeekBar 0..100 mapped to min..max).
- Size: min 3 dp, max 24 dp, default 9 dp, presets 5 / 9 / 16 dp. (Thinner default than the existing soft Brush's 12 dp: long, slender tip.)
- Default ink: sumi `0xff272320` (same as Brush).
- Optional extra SeekBar row: **Wetness**, 0..100, default 55 = amount of dry-brush (feibai) streaking vs. solid ink.
