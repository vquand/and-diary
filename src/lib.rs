#[cfg(target_os = "android")]
mod android;

#[cfg(any(target_os = "android", test))]
mod calendar;

#[cfg(target_os = "android")]
mod calendar_view;

#[cfg(target_os = "android")]
mod stickers;

#[cfg(any(target_os = "android", test))]
mod pins;
