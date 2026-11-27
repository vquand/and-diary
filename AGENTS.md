# AGENTS.md

Notes for a coding agent working in this repository. Read this before changing code or docs. The human-facing overview is [README.md](README.md). The longer design notes in [PLAN.md](PLAN.md) describe intended work that is only partly built. Confirm a behavior in the source before you document it or build on it.

## What this project is

Diary (`and-diary`) is an offline handwritten journal for Android tablets and a stylus. Version 0.1.0. Application id `io.github.vquand.anddiary`. The debug build adds `.debug` to that id and installs with the label **Diary**.

There is one app, not a set of separate tools. A single root guide is enough. Sticker artwork has its own notes in [assets/stickers/README.md](assets/stickers/README.md).

The app's own code is [PolyForm Noncommercial 1.0.0](LICENSE). Dependencies keep their licenses. `gpui-mobile` is pinned to git revision `5fd54f4554263ae06753e90472c7f2e2ff5d7495`.

## Architecture

Rust draws the shell. Kotlin draws the page you write on.

- The Rust crate `and-diary-android` (library name `and_diary`, crate-type `cdylib`) is a GPUI app via `gpui-mobile`. It owns the calendar, pins, settings, and navigation.
- `DiaryActivity` is a `NativeActivity`. The manifest meta-data `android.app.lib_name` is `and_diary`.
- The writing surface is an Android view embedded as the GPUI platform view named `diary_ink` (`INK_VIEW` in `src/android.rs`). `dev.gpui.mobile.GpuiPlatformView` hosts it. `InkSheet` is that view.
- Ink uses AndroidX Ink `1.1.0-alpha12` (`ink-authoring`, `ink-brush`, `ink-geometry`, `ink-rendering`, `ink-storage`, `ink-strokes`). The pen is `StockBrushes.pressurePen()`. The brush is a custom round tip whose width responds to pressure and to the distance remaining in the stroke.
- Rust and Kotlin meet through JNI. Opening a day passes the ISO date as a platform-view parameter. Rust switches days and drives header scrubbing through `GpuiPlatformView` static methods (`switchDate`, `beginHeaderScrub`, `updateHeaderScrub`, `endHeaderScrub`); `DayScrubberPill` draws the scrubber. `DiaryActivity` statics handle `applySettings`, edge-to-edge insets, system dark mode, the system date format, and the Kotlin-to-Rust `nativeOnDateSelected` callback.

Do not assume the stack in PLAN.md's "fallback" section (Compose for the shell) is what runs. The current shell is GPUI.

## Layout

```
src/lib.rs                  module gates (android-only UI, shared logic under test)
src/android.rs              GPUI diary: calendar, day header, settings, JNI entry
src/calendar.rs             month index (1900, 300 years), saved-day and hanzi-badge scans
src/calendar_view.rs        month grid, hand-drawn rules, pin picker
src/pins.rs                 13 calendar pins, one byte per day
src/settings.rs             theme, toolbar mode, date format, palettes
src/settings_view.rs        settings page
src/stickers.rs             pin and hanzi-badge images embedded for GPUI
app/                        Android application module
  src/main/java/io/github/vquand/anddiary/
    InkSheet.kt             toolbar, paper, gestures, undo, tools
    InkEntry.kt             .ink stroke file
    PaperItems.kt           .items JSON, sticker catalog, media copies, hanzi catalog
    PaperItemRenderer.kt    stickers, photos, media, hanzi cards on the page
    AttachmentPopovers.kt   sticker search popover and photo/video/audio picker
    ToolboxPopover.kt       ink color and size popover
    ToolbarChips.kt         pen, brush, and action chips
    ToolIcons.kt            drawn toolbar icons
    MediaPlayback.kt        inline audio, video in a dialog
    DayScrubberPill.kt      hold-to-scrub date pill
    DiaryBrushes.kt         pen, brush, eraser, swatches, sizes
    HanziPracticeDialog.kt  stroke practice UI
    HanziStrokeMatcher.kt   stroke scoring
    DiaryActivity.kt        NativeActivity, document picker, system bars, settings JNI
    DiarySettings.kt        palette and toolbar preferences on the Android side
  src/main/java/dev/gpui/mobile/
    GpuiPlatformView.kt     platform view factory
  src/test/java/.../DiaryCoreTests.kt
assets/stickers/            catalog.json, classic PNGs, sprite sheets
assets/hanzi/words.json     practice characters and normalized strokes
```

`app/build.gradle.kts` adds the repo-root `assets/` directory as Android assets and compiles the Rust library in `buildRustLibrary`.

## What is actually implemented

Trust the code over PLAN.md.

Shipped on the writing page:

- Pen, brush, eraser, select. Finger drawing is off until **Finger** is toggled. A finger otherwise scrolls.
- S Pen side button and `TOOL_TYPE_ERASER` erase. Samsung also sends motion actions 211–214 while the side button is held (`InkSheet` constants).
- Pen and brush color, plus pen, brush, and eraser size, live in shared preferences `diary_toolbox`. The eraser has no saved color. Stroke color and brush kind are stored in the `.ink` file, so a later color change affects new strokes only.
- Undo and redo, 50 snapshots, in memory for the open day. The files on disk hold the latest saved state.
- Stickers, photo, video, audio, and hanzi cards as paper items.
- Select: drag to move, corner handle to resize with the aspect ratio locked (audio has no resize handle), delete handle. Tap an audio clip to play it on the page, a video to open it in a dialog, or a hanzi card to practice.
- The page is one ruled sheet, about two viewports tall. `sheetHeight` is `height * 2`, and a resize keeps it from shrinking. It does not grow as you write. Horizontal guides are drawn every 32dp. There is no paper-template picker and no multi-section page.
- **Delete this diary** clears that day's ink, items, and copied media after confirmation. It does not remove the calendar pin.

Shipped around the page:

- Vertical month list, Monday-first grids, today ring, entry dots for `YYYY-MM-DD.ink` or `.items`.
- Hanzi badge when an items file contains a `"kind":"hanzi"` object whose `score` is greater than 0.
- Long-press (and secondary click) opens the pin picker. Pins and dots are independent.
- Settings: theme auto/light/dark, toolbar icon-only / icon-and-label / label-only, seven date formats. Rust saves `files/settings.txt` and pushes palette integers to Kotlin.

Still only in PLAN.md: choosable paper templates (blank, ruled, square grid, 田字格) and section stacking, guide spacing and paper color, backup and restore, clipboard image paste. Photos, video, and audio already arrive through `ACTION_OPEN_DOCUMENT`.

## Data on device

All of this is app-private internal storage (`context.filesDir`). `android:allowBackup="false"`. The manifest has no permissions. Do not add `INTERNET`, analytics, or accounts, and do not turn backup on, unless the task explicitly changes that product decision. Do not log diary contents.

| Path | Format |
| --- | --- |
| `files/entries/YYYY-MM-DD.ink` | Big-endian (`DataOutputStream`). Magic `0x41444931` (`ADI1`), then a stroke count, then records. Each record is brush kind (1 byte; 0 pen, 1 soft brush), ARGB color, size, epsilon, payload length (int), then that many AndroidX `StrokeInputBatch` bytes. The 17-byte record header is the kind byte plus four 4-byte fields. Empty days delete the file. |
| `files/entries/YYYY-MM-DD.items` | JSON array of `{id, kind, ref, label, durationMs, x, y, width, height, score}`. Kinds: `sticker`, `photo`, `video`, `audio`, `hanzi`. |
| `files/media/YYYY-MM-DD/` | Copies of picked media. Item `ref` is the filename. Unused files are pruned when leaving a day. |
| `files/pins/YYYY-MM-DD.pin` | One byte, the `Pin` discriminant (1–13). Ids are stable. |
| `files/settings.txt` | `theme=`, `toolbar=`, `date_format=` lines. Missing file means defaults. This is the source of truth. |

Writes go through a temp file or `AtomicFile`, then rename. Calendar dots appear only for exact `YYYY-MM-DD.ink` / `.items` names, not for `*.ink.new` or non-canonical dates. Keep that strictness.

Shared preferences sit beside those files. `diary_toolbox` stores pen and brush color and size, plus eraser size. `diary_app_settings` stores `theme_mode` and `toolbar_mode`, written when Rust calls `applySettings`. It does not store the date format. Treat `files/settings.txt` as the source of truth and the preferences as the Android-side mirror.

Sticker ids in `assets/stickers/catalog.json` are what saved items store. Classic pin artwork filenames match `src/pins.rs` and are `include_bytes!`'d as PNG in `src/stickers.rs`. Renaming a file or reusing an id for different art changes old diaries. See [assets/stickers/README.md](assets/stickers/README.md).

Hanzi strokes in `words.json` are normalized points in a 0–100 box. `HanziStrokeMatcher` scores a traced stroke against those points. The calendar badge depends on `score > 0` in the items JSON.

## Build and test

CI (`.github/workflows/ci.yml`) is the source of truth:

```sh
cargo test --locked
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Host requirements:

- Linux x86-64. `buildRustLibrary` calls `toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang`. Other host OS layouts will fail that check until the path is updated.
- JDK 17 (what CI uses). `compileOptions` target Java 17.
- `ANDROID_HOME` with SDK Platform 36, Build-Tools 36.0.0, NDK `26.3.11579264` (`ndkVersion` in `app/build.gradle.kts`).
- Rust stable new enough for edition 2024, plus `rustup target add aarch64-linux-android`.
- `local.properties` may set `sdk.dir`. That file is gitignored.

The Rust task always builds `--locked --release` for `aarch64-linux-android` and copies `liband_diary.so` into `arm64-v8a`. `minSdk` is 26, `targetSdk` and `compileSdk` are 36, `abiFilters` is `arm64-v8a` only. Linker flags include 16 KB page size.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

`cargo test --locked` runs the host tests in `calendar`, `pins`, and `settings`. Android UI modules are `cfg`'d out off Android. Kotlin unit tests are plain JVM tests in `DiaryCoreTests` and do not need a device.

## Conventions

- For new UI, take colors from `settings::Palette` (`LIGHT` / `DARK`) on the Rust side and from `DiaryPalette` on the Android side. The writing sheet, including `PaperItemRenderer`, currently paints with `DiaryPalette.LIGHT`, so the page stays ivory while the toolbar, popovers, header, and system bars follow the theme. A few surfaces stay light in the dark theme: `HanziPracticeDialog` uses its own hardcoded colors, and the delete-day and video `AlertDialog`s use `DiaryTheme`, which extends `Theme.Material.Light.NoActionBar`.
- For new Android copy, add strings in `app/src/main/res/values/strings.xml`. Calendar and settings copy is mostly hardcoded in Rust. `HanziPracticeDialog` and `PaperItemRenderer` also hardcode a few English status lines. Put new copy with the neighboring text instead of adding another hardcoded island.
- Keep entry files independent of the GPUI shell so a later UI change can still read them.
- Prefer the existing atomic write pattern for anything stored in `filesDir`.
- Pin ids, sticker ids, the ink magic number, and the items JSON keys are on-disk formats. Bump them only with a compatible reader.
- `Cargo.lock` is committed. Gradle invokes cargo with `--locked`.
- Do not commit `target/`, `app/build/`, `.gradle/`, `local.properties`, or `docs/screenshots/`.

## Personal data

Do not commit screenshots, or a device's `files/entries` or `files/media`. `docs/screenshots/` is gitignored.

## Safe changes

- Read PLAN.md as a wishlist. Implementing a planned feature is in scope only when the task asks for it. Documenting it as already shipped is not.
- UI, storage, and JNI are easy to break together. A calendar dot, a pin, and a hanzi badge are three different signals. Test the one you touched.
- `gpui-mobile` touch input does not provide pen pressure. Pressure stays on the Android ink view. Do not move live inking into GPUI.
- The platform view id `diary_ink` and the native library name `and_diary` are a contract between the manifest, Gradle, and Rust. Rename them together if you ever rename them.
