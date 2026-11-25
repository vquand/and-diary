# and-diary

A handwritten diary for Android tablets with stylus support. Scroll through the monthly calendar and tap a date to write on its sheet. Ink saves locally, and dates with saved ink show a dot. The plan also covers customizable paper sections, photos, and manual backup and restore.

The plan covers the S Pen prototype and build order. [Implementation plan](PLAN.md)

## Build the prototype

On Linux x86-64, use JDK 17, Android SDK Platform 36, Build Tools 36.0.0, NDK 26.3.11579264, and a Rust toolchain with the `aarch64-linux-android` target. Set `ANDROID_HOME` to the Android SDK directory.

```sh
rustup target add aarch64-linux-android
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk` and installs as **Diary (debug)**. The calendar opens with the current month centered and the neighboring months peeking above and below. Tap **Today** to recenter after scrolling. Hold a date to choose, replace, or remove its pin. In a dated sheet, use **Pen** or **Brush** and tap **Calendar** to return to your previous scroll position. Use **Clear day** to delete that date's writing after confirmation. Fingers scroll the paper by default. Turn on **Finger ink** to test drawing on a phone.

The interface uses warm ivory paper, charcoal text, serif calendar lettering, and vermilion accents. Hand-drawn grid lines, brushstroke rules, and an ink circle mark the calendar with subtly curved, pressure-varying strokes. Transparent raster stickers use brushed-ink artwork, bundled as 128-pixel PNGs with a shared image cache. Stickers sit in the upper-right corner of each date cell. Hold a date to choose from star, heart, flower, sun, important, cat, dog, slime, dragon, sunrise, rain, mist, and cloud. The writing toolbar and sheet share the paper palette; saved handwriting keeps its original ink colors.

## License

The project is available for noncommercial use and modification, with redistribution allowed under the license terms. Third-party components retain their own licenses. [PolyForm Noncommercial License 1.0.0](LICENSE)
