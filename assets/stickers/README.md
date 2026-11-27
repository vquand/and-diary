# Diary stickers

Original raster artwork generated for and-diary with OpenAI image generation. Each sticker uses a transparent background, textured ink edges, and watercolor washes. Files are stored as WebP images for space efficiency. They follow the project's noncommercial license.

The root pin/badge WebPs (the classic set) are `include_bytes!`'d in `src/stickers.rs` and share GPUI's decoded-image cache. Names correspond to the stable Pin IDs in `src/pins.rs`. The sprite sheets (`sheets/*.webp`) and `catalog.json`, on the other hand, are decoded on the Kotlin side by `PaperItemsStore` and cached separately.

Preserve filenames and IDs when replacing artwork so saved dates retain the intended sticker.
