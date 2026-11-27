# Diary

A handwritten journal for an Android tablet and a stylus. Flip through the months, open a day, and write on paper that stays on the device.

Diary is for people who want a private handwriting journal on a tablet: daily pages, a real pen (including an S Pen), and room on the same sheet for English script, a sketch, and Chinese character practice. Every page stays in the app's own storage.

## What you can do

- Scroll a month calendar from 1900 through 2199. The current month opens in the center, with the neighboring months peeking above and below. **Today** brings the current month back to center.
- Tap a date to open its page. A day that holds ink, a sticker, a photo, or another attachment shows a small dot. Come back with **Calendar** and the month stays where you left it.
- Write with a pressure-sensitive **Pen** or a softer **Brush**. Each tool remembers its own ink color and size, and finished strokes keep the color they were drawn with. Eight ink swatches and a custom color are built in.
- Erase with the **Eraser**, or hold the side button on an S Pen to erase while the button is down. **Undo** and **Redo** cover the last 50 changes.
- Scroll the ruled page with a finger. Turn on **Finger** when you want to draw with a fingertip, which is handy on a phone.
- Drop in stickers from five packs (capybara, lop rabbit, dogs, fluffy cats, and a classic set: 61 in all). Search by name or a tag such as happy, sleepy, or rain. The artwork is WebP.
- Attach a photo, video, or audio clip from the document picker. The app keeps its own copy, and photos are stored as WebP. **Select** draws a lasso around ink and items, then moves that area. A corner handle resizes the selection — an item or a lassoed area — keeping the shape in proportion (audio has no resize handle). Two fingers rotate, pinch-scale, and slide a selection. Tap an audio clip to play it on the page, or a video to open it in a player.
- With a selection active, open **⋯** and choose **Save selection**. The image can be PNG, JPEG, or WebP, with a transparent, plain, or ruled background, and it is saved to Gallery in `Pictures/Diary`.
- Add a Chinese practice card. Each card is a 田字格 with pinyin, Hán-Việt, and a short meaning. You can animate the stroke order, show a hint, and trace the strokes. A finished card (any score above zero) leaves a small badge on that calendar day. The bundled set has twenty characters, starting with 永.
- Step between days with the arrows, or hold the date and slide sideways to scrub. The header can follow the system date format, or a format you pick.
- Long-press a date to pin it with a star, heart, flower, an important mark, a weather mark, or a small creature. Pins and writing dots are separate. Choose **Delete this diary** in the **⋯** menu to clear that day's page after a confirmation.
- Pick a warm paper light theme, a night ink dark theme, or follow the system. The day toolbar can show icons, labels, or both.

The calendar uses serif month titles, a brushed underline, and a hand-drawn grid. The writing page stays warm ivory with faint horizontal rules in both themes. The bars around it follow the theme.

## Install

This repository does not publish release APKs. Build the debug app and install that.

On a Linux x86-64 machine, install JDK 17, Android SDK Platform 36, Build-Tools 36.0.0, and NDK 26.3.11579264. Install a current stable Rust toolchain (the crate uses the 2024 edition) and set `ANDROID_HOME` to the SDK directory.

```sh
rustup target add aarch64-linux-android
./gradlew :app:assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. It installs as **Diary**, with application id `io.github.vquand.anddiary.debug`, so it stays separate from a future non-debug install.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle build compiles the Rust library for `aarch64-linux-android` and packages it into the APK. It looks for the NDK's `linux-x86_64` toolchain, so the build host is Linux x86-64.

## Requirements

- Android 8.0 (API 26) or newer.
- A 64-bit ARM device (`arm64-v8a`). The APK ships that ABI only.
- A tablet is the layout this was designed for. A stylus is the way the page expects to be written: pressure comes through when the pen reports it, and an S Pen side button erases. Other Android styli that report themselves as a stylus can draw as well.
- A finger scrolls the page. Enable **Finger** on the day toolbar to draw without a pen.

The app creates no account and requests no network access. Saving a selection to Gallery uses the system photo library. The only storage permission is `WRITE_EXTERNAL_STORAGE`, and only through Android 9.

## A short tour

1. Open Diary. Scroll the months, or tap **Today**.
2. Tap a date. The ruled page for that day opens.
3. Write with the stylus. Switch between **Pen** and **Brush**, and tap the size chip (it shows something like `2.5dp`) to change ink color and thickness.
4. Use a finger to scroll down the page. The sheet is about two screens tall.
5. Add a sticker, a photo, a video, an audio clip, or a character card from the toolbar. **Select** lassos ink and items. Two fingers rotate and scale the selection. A corner handle resizes the selection (an item or a lassoed area), except audio.
6. Use the arrows beside the date, or hold the date and slide, to visit another day. Writing saves as you go.
7. Tap **Calendar** to return to the month. Long-press a date to add, replace, or remove its pin.
8. Open **Settings** from the calendar or from a day to change theme, toolbar labels, and date format.
9. To keep a piece of the page, select it, open **⋯**, and choose **Save selection**. To empty a day, choose **Delete this diary** and confirm. That removes the page. It leaves a calendar pin in place until you remove the pin yourself.

## Privacy and where pages live

Diary is an offline journal. Pages stay on the tablet. The manifest requests no network access, the app creates no account, and Android backup is turned off (`android:allowBackup="false"`), so the system backup leaves the diary where it is.

Everything lives in the app's private internal storage, which other apps cannot read and which is removed if you uninstall Diary:

| What | Where |
| --- | --- |
| Ink strokes for a day | `files/entries/YYYY-MM-DD.ink` |
| Stickers, photos, video, audio, and character cards | `files/entries/YYYY-MM-DD.items` |
| Copies of photos, video, and audio | `files/media/YYYY-MM-DD/` |
| Calendar pins | `files/pins/YYYY-MM-DD.pin` |
| Theme, toolbar, and date format | `files/settings.txt` |
| Android copy of theme and toolbar | preferences `diary_app_settings` |
| Pen and brush color and size, and eraser size | app preferences `diary_toolbox` |

Attached media is copied into the app. The original file stays where you picked it from. Ink is stored as the app's own stroke records, including the brush and the color used for each stroke.

## Where this is headed

The design notes in [PLAN.md](PLAN.md) go further than the app you can build today. The plan still calls for:

- Paper you can choose: blank, ruled, square grid, and 田字格, with guide spacing and paper color, and more than one section on the same day. Today the page is always faint horizontal rules, and 田字格 appears inside a character card.
- One backup file you can restore, covering entries, pins, and pictures.
- Pasting an image from the clipboard, alongside the document picker the app uses now.

## Contributing

Issues and pull requests are welcome. The project's own code is under the [PolyForm Noncommercial License 1.0.0](LICENSE): personal, research, and other noncommercial use. Dependencies keep their own licenses. Check those licenses before you distribute an APK.

Before a change:

```sh
cargo test --locked
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Please keep real diary pages, names, device data, and screenshots out of the repository. Calendar pin ids and sticker ids in `assets/stickers/catalog.json` are part of saved data. [assets/stickers/README.md](assets/stickers/README.md) covers replacing the classic pin artwork.

People working in this tree with a coding agent can start from [AGENTS.md](AGENTS.md).

## License

[PolyForm Noncommercial License 1.0.0](LICENSE). Third-party components retain their own licenses.
