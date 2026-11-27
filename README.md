# and-diary

A handwritten diary for Android tablets with stylus support. Scroll through the monthly calendar and tap a date to write on its sheet. Ink saves locally, and dates with saved ink show a dot. The plan also covers customizable paper sections, photos, and manual backup and restore.

The plan covers the S Pen prototype and build order. [Implementation plan](PLAN.md)

## Build the prototype

On Linux x86-64, use JDK 17, Android SDK Platform 36, Build Tools 36.0.0, NDK 26.3.11579264, and a Rust toolchain with the `aarch64-linux-android` target. Set `ANDROID_HOME` to the Android SDK directory.

```sh
rustup target add aarch64-linux-android
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk` and installs as **Diary (debug)**. The calendar opens with the current month centered and the neighboring months peeking above and below. Tap **Today** to recenter after scrolling. Hold a date to choose, replace, or remove its pin. In a dated sheet, use **Pen**, **Brush**, or the customizable third brush slot backed by the **Brush bank** (tap the gear button in the third slot's popover to star a brush such as Pencil, Chinese Ink Brush, Calligraphy Quill, Airbrush, or Charcoal), and tap **Calendar** to return to your previous scroll position. Use **Clear day** to delete that date's writing after confirmation. Fingers scroll the paper by default. Turn on **Finger ink** to test drawing on a phone.

The interface uses warm ivory paper, charcoal text, serif calendar lettering, and vermilion accents. Hand-drawn grid lines, brushstroke rules, and an ink circle mark the calendar with subtly curved, pressure-varying strokes. Transparent raster stickers use brushed-ink artwork, bundled as 128-pixel PNGs with a shared image cache. Stickers sit in the upper-right corner of each date cell. Hold a date to choose from star, heart, flower, sun, important, cat, dog, slime, dragon, sunrise, rain, mist, and cloud. The writing toolbar and sheet share the paper palette; saved handwriting keeps its original ink colors.

## Adding a brush

1. Add a `BrushSpec` entry to `BrushBank.all` in `app/src/main/java/io/github/vquand/anddiary/BrushBank.kt` with a unique `id` and the next unused `storageCode` (starting after `20`; never reuse retired codes).
2. Add a display string in `app/src/main/res/values/strings.xml` and a `ToolIconKind` drawing case in `app/src/main/java/io/github/vquand/anddiary/ToolIcons.kt`.
3. Drop an optional `stamp.webp` (or `stamp.png`) into `design/brush-bank/<id>/` and run `python3 tools/import_brush_assets.py` to write a lossless WebP with alpha at `assets/brushes/<id>/stamp.webp` (and regenerate star drawables). The app's minSdk is 26, well above API 18 where Android can decode lossless WebP with alpha.
4. See [docs/ink-format.md](docs/ink-format.md) for how `.ink` stroke records reference brush storage codes.

## License

The project is available for noncommercial use and modification, with redistribution allowed under the license terms. Third-party components retain their own licenses. [PolyForm Noncommercial License 1.0.0](LICENSE)
