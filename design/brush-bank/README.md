# Brush Bank Design Assets (Slot 3)

Designer source files and notes for the 5 starter bank brushes (`pencil`, `chinese-ink-brush`, `calligraphy-quill`, `airbrush`, `charcoal`).

## Layout

| Path | Description |
| --- | --- |
| `<brush-id>/icon.svg` | 24×24 single-colour `#000000` icon (stroke `2.04`, round caps/joins); source reference for `ToolIconView` paths |
| `<brush-id>/notes.md` | Stroke behaviour, texture mapping, and config dialog parameters |
| `ToolIconsNew.kt.txt` | Unit-square (`0..1`) `ToolIconView` Canvas path code corresponding to `<brush-id>/icon.svg` |
| `star-on.svg`, `star-off.svg` | Filled / outline star toggle for the brush bank dialog |
| `slot3-button.svg` | Optional toolbar slot-3 badge reference |

## Runtime asset locations & swapping assets

- **Brush icons**: Drawn directly in code via `ToolIconKind` entries in `app/src/main/java/io/github/vquand/anddiary/ToolIcons.kt` (matching `Pen` and `Brush`).
- **Star & settings icons**: Vector drawables at `app/src/main/res/drawable/ic_star_on.xml`, `ic_star_off.xml`, and `ic_brush_bank_settings.xml`. To re-import `star-on.svg` / `star-off.svg` and any updated `stamp.webp` or `stamp.png` files into Android resources/assets, run:
  ```sh
  python3 tools/import_brush_assets.py
  ```
- **Stamps / textures**: Shipped as lossless WebP with alpha at `assets/brushes/<brush-id>/stamp.webp` (white RGB, alpha = coverage). The importer copies a source `stamp.webp` or converts a source `stamp.png`. If a WebP is absent at runtime, `AssetAndProceduralTextureStore` falls back to deterministic procedural stamps in `BrushStampGenerator`. Android has decoded lossless WebP with alpha since API 18; this app's minSdk is 26.
- **Texture mapping note (`androidx.ink 1.1.0-alpha12`)**:
  - `pencil` and `charcoal` use world-anchored `TilingTexture` (`Origin.STROKE_SPACE_ORIGIN`, `SizeUnit.STROKE_COORDINATES`, `128dp` tile = `0.5dp`/texel for 256px stamps).
  - `chinese-ink-brush` uses `TilingTexture` (`Origin.FIRST_STROKE_INPUT`, `SizeUnit.BRUSH_SIZE`) because `1.1.0-alpha12` does not expose arclength stroke-aligned UV mapping.
  - `airbrush` uses `StampingTexture` (`stamp.webp`, `particleGapDistanceScale = 0.08f`) with a soft `BrushPaint` fallback in `paintPreferences` for API <34 / software canvases.
  - Si's airbrush `gradient.png` (1-D cross-section profile) is not shipped: `1.1.0-alpha12` has no across-stroke UV mapping to apply it. The soft stamp gives the falloff instead.

## Follow-up ideas from designer notes (deferred)

Per-brush extra `SeekBar` rows in `ToolboxPopover` are intentionally deferred to a follow-up:
- Pencil: **Softness** (HB ↔ 6B grain contrast)
- Chinese ink brush: **Wetness** (dry-brush streaking vs. solid ink)
- Calligraphy quill: **Nib angle** (0°–90°)
- Airbrush: **Flow** (per-stamp opacity)
- Charcoal: **Grain** (grain contrast / hole size)
