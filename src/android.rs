use crate::{
    calendar,
    calendar_view::{self, DateAction, PinAction},
    pins::{self, Pin},
    settings::{self, AppSettings, DateFormat, Palette, ThemeMode},
    settings_view::{self, SettingsAction},
};
use chrono::{Datelike, Local, NaiveDate};
use gpui::{
    App, Application, Context, HitboxBehavior, IntoElement, LongPressEvent, Render, ScrollStrategy,
    Task, TouchPhase, UniformListScrollHandle, Window, WindowOptions, canvas, div, prelude::*, px,
    rgb,
};
use gpui_mobile::{
    android::{jni as android_jni, platform_view::AndroidPlatformViewFactory},
    components::platform_view_element::ManagedPlatformView,
    platform_view::{PlatformViewBounds, PlatformViewRegistry},
};
use jni::{
    jni_sig, jni_str,
    objects::{JIntArray, JValue},
};
use std::{
    cell::Cell,
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
    rc::Rc,
    sync::{
        Mutex,
        atomic::{AtomicBool, Ordering},
    },
};

static PENDING_DATE: Mutex<Option<NaiveDate>> = Mutex::new(None);
static PENDING_DAYS_REFRESH: AtomicBool = AtomicBool::new(false);

fn clear_pending_date() {
    if let Ok(mut pending) = PENDING_DATE.lock() {
        *pending = None;
    }
}

const INK_VIEW: &str = "diary_ink";
const HEADER_HEIGHT: f32 = 56.;
// Sentinel bounds that never match PlatformViewRegistry::hit_test while header scrubbing is active,
// so gpui-mobile keeps routing touch Move/Up events to GPUI even when the finger slides over the sheet.
const EMPTY_HIT_BOUNDS: PlatformViewBounds = PlatformViewBounds {
    x: -1.,
    y: -1.,
    width: -1.,
    height: -1.,
};

struct DaySheet {
    date: NaiveDate,
    ink: ManagedPlatformView,
    ink_bounds: Rc<Cell<PlatformViewBounds>>,
    scrub_down_x: Option<f32>,
    scrub_active: bool,
}

struct Diary {
    entry: Option<DaySheet>,
    return_date: Option<NaiveDate>,
    show_settings: bool,
    directory: PathBuf,
    saved_days: Rc<BTreeSet<NaiveDate>>,
    hanzi_days: Rc<BTreeSet<NaiveDate>>,
    days_scan_task: Option<Task<()>>,
    pins: Rc<BTreeMap<NaiveDate, Pin>>,
    pin_directory: PathBuf,
    pin_picker: Option<(NaiveDate, bool)>,
    settings_path: PathBuf,
    settings: AppSettings,
    scroll: UniformListScrollHandle,
    today: NaiveDate,
}

impl Diary {
    fn new(files: PathBuf, window: &mut Window, cx: &mut Context<Self>) -> Self {
        cx.observe_window_activation(window, |diary, window, _| {
            if !window.is_window_active() {
                diary.finish_header_scrub(true);
            }
        })
        .detach();
        let directory = files.join("entries");
        let pin_directory = files.join("pins");
        let settings_path = files.join("settings.txt");
        let settings = settings::load(&settings_path);
        apply_android_settings(settings);
        let today = Local::now().date_naive();
        let scroll = UniformListScrollHandle::new();
        scroll.scroll_to_item_strict(calendar::month_index(today), ScrollStrategy::Center);
        let mut diary = Self {
            entry: None,
            return_date: None,
            show_settings: false,
            saved_days: Rc::new(BTreeSet::new()),
            hanzi_days: Rc::new(BTreeSet::new()),
            days_scan_task: None,
            directory,
            pins: Rc::new(pins::load(&pin_directory)),
            pin_directory,
            pin_picker: None,
            settings_path,
            settings,
            scroll,
            today,
        };
        diary.refresh_days(cx);
        diary
    }

    fn refresh_days(&mut self, cx: &mut Context<Self>) {
        let directory = self.directory.clone();
        self.days_scan_task = Some(cx.spawn(async move |this, cx| {
            let (saved, hanzi) = cx
                .background_executor()
                .spawn(async move {
                    (
                        calendar::saved_days(&directory),
                        calendar::completed_hanzi_days(&directory),
                    )
                })
                .await;
            let _ = this.update(cx, |diary, cx| {
                diary.saved_days = Rc::new(saved);
                diary.hanzi_days = Rc::new(hanzi);
                cx.notify();
            });
        }));
    }

    fn finish_header_scrub(&mut self, canceled: bool) -> bool {
        let Some(entry) = &mut self.entry else {
            return false;
        };
        if !entry.scrub_active {
            return false;
        }
        entry.scrub_down_x = None;
        entry.scrub_active = false;
        if let Some(handle) = entry.ink.handle() {
            PlatformViewRegistry::global()
                .update_view_bounds(handle.id(), entry.ink_bounds.get());
        }
        end_header_scrub(canceled);
        true
    }

    fn calendar(&mut self, cx: &mut Context<Self>) {
        clear_pending_date();
        PENDING_DAYS_REFRESH.store(false, Ordering::Release);
        self.entry = None;
        self.return_date = None;
        self.show_settings = false;
        self.refresh_days(cx);
        self.today = Local::now().date_naive();
        cx.notify();
    }

    fn open_day(&mut self, date: NaiveDate, cx: &mut Context<Self>) {
        clear_pending_date();
        self.show_settings = false;
        self.return_date = None;
        self.entry = Some(DaySheet {
            date,
            ink: ManagedPlatformView::new(INK_VIEW).with_param("date", date.to_string()),
            ink_bounds: Rc::new(Cell::new(PlatformViewBounds::default())),
            scrub_down_x: None,
            scrub_active: false,
        });
        cx.notify();
    }

    fn navigate_day(&mut self, date: NaiveDate, cx: &mut Context<Self>) {
        clear_pending_date();
        if let Some(entry) = &mut self.entry {
            if entry.date == date {
                return;
            }
            entry.date = date;
            switch_android_day(date);
            cx.notify();
        } else {
            self.open_day(date, cx);
        }
    }

    fn open_settings(&mut self, cx: &mut Context<Self>) {
        clear_pending_date();
        self.return_date = self.entry.as_ref().map(|entry| entry.date);
        self.entry = None;
        self.pin_picker = None;
        self.show_settings = true;
        cx.notify();
    }

    fn close_settings(&mut self, cx: &mut Context<Self>) {
        self.show_settings = false;
        if let Some(date) = self.return_date.take() {
            self.open_day(date, cx);
        } else {
            cx.notify();
        }
    }

    fn update_settings(&mut self, action: SettingsAction, cx: &mut Context<Self>) {
        match action {
            SettingsAction::SetTheme(theme) => self.settings.theme = theme,
            SettingsAction::SetToolbar(toolbar) => self.settings.toolbar = toolbar,
            SettingsAction::SetDateFormat(date_format) => self.settings.date_format = date_format,
            SettingsAction::TogglePlugin(plugin) => self.settings.toggle_plugin(plugin),
        }
        if let Err(error) = settings::save(&self.settings_path, self.settings) {
            eprintln!("could not save settings: {error}");
        }
        apply_android_settings(self.settings);
        cx.notify();
    }

    fn update_pin(&mut self, date: NaiveDate, action: PinAction, cx: &mut Context<Self>) {
        if let PinAction::Set(pin) = action {
            if pins::save(&self.pin_directory, date, pin).is_err() {
                self.pin_picker = Some((date, true));
                cx.notify();
                return;
            }
            let pins = Rc::make_mut(&mut self.pins);
            if let Some(pin) = pin {
                pins.insert(date, pin);
            } else {
                pins.remove(&date);
            }
        }
        self.pin_picker = None;
        cx.notify();
    }
}

impl Render for Diary {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if let Some(synced) = PENDING_DATE.lock().ok().and_then(|mut guard| guard.take())
            && let Some(entry) = &mut self.entry
        {
            entry.date = synced;
        }
        if self.entry.is_none()
            && !self.show_settings
            && PENDING_DAYS_REFRESH.swap(false, Ordering::AcqRel)
        {
            self.refresh_days(cx);
        }

        let [left, top, right, bottom] = android_jni::platform()
            .and_then(|platform| platform.primary_window())
            .map(|window| {
                let insets = window.safe_area_insets_logical();
                let scale = window.scale_factor();
                let [left, top, right, bottom] = edge_to_edge_insets();
                [
                    insets.left.max(left as f32 / scale),
                    insets.top.max(top as f32 / scale),
                    insets.right.max(right as f32 / scale),
                    insets.bottom.max(bottom as f32 / scale),
                ]
            })
            .unwrap_or_default();

        let system_dark = if self.settings.theme == ThemeMode::Auto {
            is_system_dark_mode()
        } else {
            false
        };
        let palette = Palette::resolve(self.settings.theme, system_dark);

        let header = div()
            .h(px(HEADER_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .justify_between()
            .gap(px(12.))
            .px(px(16.))
            .border_b_1()
            .border_color(rgb(palette.rule));

        let root = div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .pt(px(top))
            .pb(px(bottom))
            .pl(px(left))
            .pr(px(right))
            .bg(rgb(palette.paper))
            .text_color(rgb(palette.text));

        if self.show_settings {
            let back_label = if self.return_date.is_some() {
                "‹ Diary"
            } else {
                "‹ Calendar"
            };
            let diary = cx.entity().downgrade();
            return root
                .child(
                    header
                        .child(
                            header_button("settings-back", back_label, palette)
                                .on_click(cx.listener(|diary, _, _, cx| diary.close_settings(cx))),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_center()
                                .text_sm()
                                .font_weight(gpui::FontWeight::SEMIBOLD)
                                .child("Settings"),
                        )
                        .child(div().w(px(84.)).flex_shrink_0()),
                )
                .child(settings_view::settings_page(
                    self.settings,
                    palette,
                    move |action, _, app| {
                        let _ = diary.update(app, |diary, cx| {
                            diary.update_settings(action, cx);
                        });
                    },
                ));
        }

        if let Some(entry) = &mut self.entry {
            let current_date = entry.date;
            let date = format_day_header(current_date, self.settings.date_format);
            let scrub_diary = cx.entity().downgrade();
            return root
                .child(
                    header
                        .child(
                            header_button("calendar", "‹ Calendar", palette)
                                .on_click(cx.listener(|diary, _, _, cx| diary.calendar(cx))),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .items_center()
                                .justify_center()
                                .gap(px(10.))
                                .child(
                                    day_nav_button("prev-day", "‹", palette).on_click(
                                        cx.listener(move |diary, _, _, cx| {
                                            if let Some(prev) = current_date.pred_opt() {
                                                diary.navigate_day(prev, cx);
                                            }
                                        }),
                                    ),
                                )
                                .child(
                                    div()
                                        .id("day-date-pill")
                                        .relative()
                                        .h(px(40.))
                                        .px(px(12.))
                                        .flex_shrink_0()
                                        .flex()
                                        .items_center()
                                        .justify_center()
                                        .rounded_md()
                                        .cursor_pointer()
                                        .active(move |style| style.bg(rgb(palette.wash)))
                                        .text_center()
                                        .text_size(px(15.))
                                        .font_weight(gpui::FontWeight::SEMIBOLD)
                                        .child(date)
                                        .child(
                                            canvas(
                                                |bounds, window, _| {
                                                    window.insert_hitbox(
                                                        bounds,
                                                        HitboxBehavior::Normal,
                                                    )
                                                },
                                                move |_, hitbox, window, _| {
                                                    let hold_diary = scrub_diary.clone();
                                                    window.on_mouse_event(
                                                        move |event: &LongPressEvent,
                                                              phase,
                                                              window,
                                                              app| {
                                                            if !phase.bubble() {
                                                                return;
                                                            }
                                                            let x = event.position.x.as_f32();
                                                            match event.phase {
                                                                TouchPhase::Started => {
                                                                    if hitbox.is_hovered(window)
                                                                        && !window
                                                                            .default_prevented()
                                                                    {
                                                                        window.prevent_default();
                                                                        app.stop_propagation();
                                                                        let _ = hold_diary.update(
                                                                            app,
                                                                            |diary, _| {
                                                                                if let Some(entry) =
                                                                                    &mut diary.entry
                                                                                    && !entry.scrub_active
                                                                                {
                                                                                    entry.scrub_down_x =
                                                                                        Some(x);
                                                                                    entry.scrub_active =
                                                                                        true;
                                                                                    if let Some(handle) =
                                                                                        entry.ink.handle()
                                                                                    {
                                                                                        PlatformViewRegistry::global()
                                                                                            .update_view_bounds(
                                                                                                handle.id(),
                                                                                                EMPTY_HIT_BOUNDS,
                                                                                            );
                                                                                    }
                                                                                    begin_header_scrub();
                                                                                }
                                                                            },
                                                                        );
                                                                    }
                                                                }
                                                                TouchPhase::Moved => {
                                                                    let _ = hold_diary.update(
                                                                        app,
                                                                        |diary, _| {
                                                                            if let Some(entry) =
                                                                                &mut diary.entry
                                                                                && entry.scrub_active
                                                                                && let Some(down_x) =
                                                                                    entry.scrub_down_x
                                                                            {
                                                                                window.prevent_default();
                                                                                update_header_scrub(
                                                                                    x - down_x,
                                                                                );
                                                                            }
                                                                        },
                                                                    );
                                                                }
                                                                TouchPhase::Ended
                                                                | TouchPhase::Cancelled => {
                                                                    let canceled = event.phase
                                                                        == TouchPhase::Cancelled;
                                                                    let _ = hold_diary.update(
                                                                        app,
                                                                        |diary, _| {
                                                                            if diary
                                                                                .finish_header_scrub(
                                                                                    canceled,
                                                                                )
                                                                            {
                                                                                window
                                                                                    .prevent_default();
                                                                            }
                                                                        },
                                                                    );
                                                                }
                                                            }
                                                        },
                                                    );
                                                },
                                            )
                                            .absolute()
                                            .inset_0(),
                                        ),
                                )
                                .child(
                                    day_nav_button("next-day", "›", palette).on_click(
                                        cx.listener(move |diary, _, _, cx| {
                                            if let Some(next) = current_date.succ_opt() {
                                                diary.navigate_day(next, cx);
                                            }
                                        }),
                                    ),
                                ),
                        )
                        .child(
                            header_button("day-settings", "Settings", palette)
                                .on_click(cx.listener(|diary, _, _, cx| diary.open_settings(cx))),
                        ),
                )
                .child({
                    let scrub_active = entry.scrub_active;
                    let ink_bounds = entry.ink_bounds.clone();
                    let ink_view = entry.ink.render().size_full();
                    let ink_id = entry.ink.handle().map(|handle| handle.id());
                    div()
                        .relative()
                        .w_full()
                        .flex_1()
                        .min_h_0()
                        .child(ink_view)
                        .child(
                            canvas(
                                |bounds, _, _| PlatformViewBounds {
                                    x: bounds.origin.x.as_f32(),
                                    y: bounds.origin.y.as_f32(),
                                    width: bounds.size.width.as_f32(),
                                    height: bounds.size.height.as_f32(),
                                },
                                move |_, bounds, _, _| {
                                    ink_bounds.set(bounds);
                                    if scrub_active && let Some(id) = ink_id {
                                        PlatformViewRegistry::global()
                                            .update_view_bounds(id, EMPTY_HIT_BOUNDS);
                                    }
                                },
                            )
                            .absolute()
                            .inset_0(),
                        )
                });
        }

        let diary = cx.entity().downgrade();
        root.child(
            header
                .child(
                    div()
                        .relative()
                        .font_family("Noto Serif")
                        .text_size(px(25.))
                        .child("Diary")
                        .child(
                            calendar_view::brush_rule(palette.text)
                                .absolute()
                                .bottom(px(-3.))
                                .left_0(),
                        ),
                )
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(4.))
                        .child(header_button("today", "Today", palette).on_click(cx.listener(
                            |diary, _, _, cx| {
                                diary.today = Local::now().date_naive();
                                diary.scroll.scroll_to_item_strict(
                                    calendar::month_index(diary.today),
                                    ScrollStrategy::Center,
                                );
                                cx.notify();
                            },
                        )))
                        .child(
                            header_button("settings", "Settings", palette)
                                .on_click(cx.listener(|diary, _, _, cx| diary.open_settings(cx))),
                        ),
                ),
        )
        .child(calendar_view::months(
            self.today,
            self.saved_days.clone(),
            self.hanzi_days.clone(),
            self.pins.clone(),
            palette,
            &self.scroll,
            window.viewport_size().height - px(top + bottom + HEADER_HEIGHT),
            move |date, action, _, app| {
                let _ = diary.update(app, |diary, cx| {
                    match action {
                        DateAction::Open => diary.open_day(date, cx),
                        DateAction::PickPin => {
                            diary.pin_picker = Some((date, false));
                            cx.notify();
                        }
                    }
                });
            },
        ))
        .when_some(self.pin_picker, |root, (date, failed)| {
            let current = self.pins.get(&date).copied();
            root.child(calendar_view::pin_picker(
                date,
                current,
                failed,
                palette,
                cx.listener(move |diary, action: &PinAction, _, cx| {
                    diary.update_pin(date, *action, cx);
                }),
            ))
        })
    }
}

fn day_nav_button(
    id: &'static str,
    label: &'static str,
    palette: Palette,
) -> gpui::Stateful<gpui::Div> {
    div()
        .id(id)
        .focusable()
        .w(px(44.))
        .h(px(40.))
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .rounded_md()
        .border_1()
        .border_color(rgb(palette.rule))
        .bg(rgb(palette.wash))
        .text_size(px(22.))
        .text_color(rgb(palette.accent))
        .font_weight(gpui::FontWeight::SEMIBOLD)
        .cursor_pointer()
        .active(move |style| style.bg(rgb(palette.rule)))
        .focus_visible(move |style| style.bg(rgb(palette.rule)))
        .child(label)
}

fn header_button(
    id: &'static str,
    label: &'static str,
    palette: Palette,
) -> gpui::Stateful<gpui::Div> {
    div()
        .id(id)
        .focusable()
        .h(px(44.))
        .px(px(10.))
        .flex_shrink_0()
        .flex()
        .items_center()
        .rounded_sm()
        .text_color(rgb(palette.accent))
        .font_weight(gpui::FontWeight::SEMIBOLD)
        .cursor_pointer()
        .active(move |style| style.bg(rgb(palette.wash)))
        .focus_visible(move |style| style.bg(rgb(palette.wash)))
        .child(label)
}

fn edge_to_edge_insets() -> [i32; 4] {
    android_jni::with_env(|env| {
        let activity = android_jni::activity(env)?;
        let class = android_jni::find_app_class(env, "io.github.vquand.anddiary.DiaryActivity")?;
        let result: jni::errors::Result<_> = (|| {
            let value = env.call_static_method(
                &class,
                jni_str!("edgeToEdgeInsets"),
                jni_sig!("(Landroid/app/Activity;)[I"),
                &[JValue::Object(&activity)],
            )?;
            let array = JIntArray::cast_local(env, value.l()?)?;
            let mut insets = [0; 4];
            array.get_region(env, 0, &mut insets)?;
            Ok(insets)
        })();
        if result.is_err() {
            env.exception_clear();
        }
        result.map_err(|error| error.to_string())
    })
    .unwrap_or_default()
}

fn format_day_header(date: NaiveDate, format: DateFormat) -> String {
    if let Some(custom) = format.format_custom(date) {
        return custom;
    }
    android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "io.github.vquand.anddiary.DiaryActivity")?;
        let result: jni::errors::Result<_> = (|| {
            let value = env.call_static_method(
                &class,
                jni_str!("formatSystemDate"),
                jni_sig!("(III)Ljava/lang/String;"),
                &[
                    JValue::Int(date.year()),
                    JValue::Int(date.month() as i32),
                    JValue::Int(date.day() as i32),
                ],
            )?;
            let jstr = jni::objects::JString::cast_local(env, value.l()?)?;
            let text = jstr.to_string();
            Ok(text)
        })();
        if result.is_err() {
            env.exception_clear();
        }
        result.map_err(|error| error.to_string())
    })
    .unwrap_or_else(|_| date.format("%a, %b %-d, %Y").to_string())
}

fn is_system_dark_mode() -> bool {
    android_jni::with_env(|env| {
        let activity = android_jni::activity(env)?;
        let class = android_jni::find_app_class(env, "io.github.vquand.anddiary.DiaryActivity")?;
        let result: jni::errors::Result<_> = (|| {
            let value = env.call_static_method(
                &class,
                jni_str!("isSystemDarkMode"),
                jni_sig!("(Landroid/app/Activity;)Z"),
                &[JValue::Object(&activity)],
            )?;
            value.z()
        })();
        if result.is_err() {
            env.exception_clear();
        }
        result.map_err(|error| error.to_string())
    })
    .unwrap_or(false)
}

fn begin_header_scrub() {
    let _ = android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "dev.gpui.mobile.GpuiPlatformView")?;
        let result = env.call_static_method(
            &class,
            jni_str!("beginHeaderScrub"),
            jni_sig!("()V"),
            &[],
        );
        if result.is_err() {
            env.exception_clear();
        }
        result.map(|_| ()).map_err(|error| error.to_string())
    });
}

fn update_header_scrub(dx_dp: f32) {
    let _ = android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "dev.gpui.mobile.GpuiPlatformView")?;
        let result = env.call_static_method(
            &class,
            jni_str!("updateHeaderScrub"),
            jni_sig!("(F)V"),
            &[JValue::Float(dx_dp)],
        );
        if result.is_err() {
            env.exception_clear();
        }
        result.map(|_| ()).map_err(|error| error.to_string())
    });
}

fn end_header_scrub(canceled: bool) {
    let _ = android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "dev.gpui.mobile.GpuiPlatformView")?;
        let result = env.call_static_method(
            &class,
            jni_str!("endHeaderScrub"),
            jni_sig!("(Z)V"),
            &[JValue::Bool(canceled)],
        );
        if result.is_err() {
            env.exception_clear();
        }
        result.map(|_| ()).map_err(|error| error.to_string())
    });
}

fn switch_android_day(date: NaiveDate) {
    let _ = android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "dev.gpui.mobile.GpuiPlatformView")?;
        let date = env
            .new_string(date.to_string())
            .map_err(|error| error.to_string())?;
        let result = env.call_static_method(
            &class,
            jni_str!("switchDate"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(date.as_ref())],
        );
        if result.is_err() {
            env.exception_clear();
        }
        result.map(|_| ()).map_err(|error| error.to_string())
    });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vquand_anddiary_DiaryActivity_nativeOnDateSelected(
    _env: jni::EnvUnowned<'_>,
    _class: jni::objects::JClass<'_>,
    year: i32,
    month: i32,
    day: i32,
) {
    if let Some(date) = NaiveDate::from_ymd_opt(year, month as u32, day as u32) {
        if let Ok(mut pending) = PENDING_DATE.lock() {
            *pending = Some(date);
        }
        if let Some(window) = android_jni::platform().and_then(|platform| platform.primary_window())
        {
            window.request_frame();
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_vquand_anddiary_DiaryActivity_nativeOnDayWritten(
    _env: jni::EnvUnowned<'_>,
    _class: jni::objects::JClass<'_>,
) {
    PENDING_DAYS_REFRESH.store(true, Ordering::Release);
    if let Some(window) = android_jni::platform().and_then(|platform| platform.primary_window()) {
        window.request_frame();
    }
}

fn apply_android_settings(settings: AppSettings) {
    let _ = android_jni::with_env(|env| {
        let activity = android_jni::activity(env)?;
        let class = android_jni::find_app_class(env, "io.github.vquand.anddiary.DiaryActivity")?;
        let result: jni::errors::Result<_> = (|| {
            let light = env.new_int_array(7)?;
            light.set_region(env, 0, &Palette::LIGHT.to_argb_array())?;
            let dark = env.new_int_array(7)?;
            dark.set_region(env, 0, &Palette::DARK.to_argb_array())?;
            env.call_static_method(
                &class,
                jni_str!("applySettings"),
                jni_sig!("(Landroid/app/Activity;III[I[I)V"),
                &[
                    JValue::Object(&activity),
                    JValue::Int(settings.theme.code()),
                    JValue::Int(settings.toolbar.code()),
                    JValue::Int(settings.plugins_mask()),
                    JValue::Object(&light),
                    JValue::Object(&dark),
                ],
            )?;
            Ok(())
        })();
        if result.is_err() {
            env.exception_clear();
        }
        result.map_err(|error| error.to_string())
    });
}

#[unsafe(no_mangle)]
fn android_main(app: android_activity::AndroidApp) {
    let directory = app.internal_data_path().expect("Android files directory");
    android_jni::install_panic_hook();
    android_jni::init_platform(&app);
    PlatformViewRegistry::global().register(
        INK_VIEW,
        Box::new(AndroidPlatformViewFactory::new(INK_VIEW)),
    );
    let platform = android_jni::shared_platform().expect("Android platform was initialized");
    Application::with_platform(platform.into_rc()).run(move |app: &mut App| {
        app.open_window(WindowOptions::default(), move |window, app| {
            app.new(|cx| Diary::new(directory, window, cx))
        })
        .expect("open diary window");
    });
}
