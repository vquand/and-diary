use crate::pins::Pin;
use gpui::{Div, Image, ImageFormat, div, img, prelude::*, px};
use std::sync::{Arc, LazyLock};

// Share encoded images and GPUI's decoded-image cache across all visible dates.
static STICKERS: LazyLock<[Arc<Image>; 13]> = LazyLock::new(|| {
    let bytes: [&[u8]; 13] = [
        include_bytes!("../assets/stickers/star.webp"),
        include_bytes!("../assets/stickers/heart.webp"),
        include_bytes!("../assets/stickers/flower.webp"),
        include_bytes!("../assets/stickers/sun.webp"),
        include_bytes!("../assets/stickers/important.webp"),
        include_bytes!("../assets/stickers/cat.webp"),
        include_bytes!("../assets/stickers/dog.webp"),
        include_bytes!("../assets/stickers/slime.webp"),
        include_bytes!("../assets/stickers/dragon.webp"),
        include_bytes!("../assets/stickers/sunrise.webp"),
        include_bytes!("../assets/stickers/rain.webp"),
        include_bytes!("../assets/stickers/mist.webp"),
        include_bytes!("../assets/stickers/cloud.webp"),
    ];
    bytes.map(|data| Arc::new(Image::from_bytes(ImageFormat::Webp, data.to_vec())))
});

static HANZI_BADGE: LazyLock<Arc<Image>> = LazyLock::new(|| {
    Arc::new(Image::from_bytes(
        ImageFormat::Webp,
        include_bytes!("../assets/stickers/hanzi_done.webp").to_vec(),
    ))
});

pub fn symbol(pin: Pin) -> Div {
    div()
        .size(px(24.))
        .child(img(STICKERS[pin as usize - 1].clone()).size(px(24.)))
}

pub fn hanzi_badge() -> Div {
    div()
        .size(px(20.))
        .child(img(HANZI_BADGE.clone()).size(px(20.)))
}
