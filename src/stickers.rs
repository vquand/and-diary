use crate::pins::Pin;
use gpui::{Div, Image, ImageFormat, div, img, prelude::*, px};
use std::sync::{Arc, LazyLock};

// Share encoded images and GPUI's decoded-image cache across all visible dates.
static STICKERS: LazyLock<[Arc<Image>; 13]> = LazyLock::new(|| {
    let bytes: [&[u8]; 13] = [
        include_bytes!("../assets/stickers/star.png"),
        include_bytes!("../assets/stickers/heart.png"),
        include_bytes!("../assets/stickers/flower.png"),
        include_bytes!("../assets/stickers/sun.png"),
        include_bytes!("../assets/stickers/important.png"),
        include_bytes!("../assets/stickers/cat.png"),
        include_bytes!("../assets/stickers/dog.png"),
        include_bytes!("../assets/stickers/slime.png"),
        include_bytes!("../assets/stickers/dragon.png"),
        include_bytes!("../assets/stickers/sunrise.png"),
        include_bytes!("../assets/stickers/rain.png"),
        include_bytes!("../assets/stickers/mist.png"),
        include_bytes!("../assets/stickers/cloud.png"),
    ];
    bytes.map(|data| Arc::new(Image::from_bytes(ImageFormat::Png, data.to_vec())))
});

static HANZI_BADGE: LazyLock<Arc<Image>> = LazyLock::new(|| {
    Arc::new(Image::from_bytes(
        ImageFormat::Png,
        include_bytes!("../assets/stickers/hanzi_done.png").to_vec(),
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
