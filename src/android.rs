use crate::{
    calendar,
    calendar_view::{self, DateAction, PinAction},
    pins::{self, Pin},
};
use chrono::{Local, NaiveDate};
use gpui::{
    App, Application, Context, IntoElement, Render, ScrollStrategy, UniformListScrollHandle,
    Window, WindowOptions, div, prelude::*, px, rgb,
};
use gpui_mobile::{
    android::{jni as android_jni, platform_view::AndroidPlatformViewFactory},
    components::platform_view_element::ManagedPlatformView,
    platform_view::PlatformViewRegistry,
};
use jni::{
    jni_sig, jni_str,
    objects::{JIntArray, JValue},
};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::PathBuf,
    rc::Rc,
};

const INK_VIEW: &str = "diary_ink";
const HEADER_HEIGHT: f32 = 56.;

struct DaySheet {
    date: NaiveDate,
    ink: ManagedPlatformView,
}

struct Diary {
    entry: Option<DaySheet>,
    directory: PathBuf,
    saved_days: Rc<BTreeSet<NaiveDate>>,
    pins: Rc<BTreeMap<NaiveDate, Pin>>,
    pin_directory: PathBuf,
    pin_picker: Option<(NaiveDate, bool)>,
    scroll: UniformListScrollHandle,
    today: NaiveDate,
}

impl Diary {
    fn new(files: PathBuf) -> Self {
        let directory = files.join("entries");
        let pin_directory = files.join("pins");
        let today = Local::now().date_naive();
        let scroll = UniformListScrollHandle::new();
        scroll.scroll_to_item_strict(calendar::month_index(today), ScrollStrategy::Center);
        Self {
            entry: None,
            saved_days: Rc::new(calendar::saved_days(&directory)),
            directory,
            pins: Rc::new(pins::load(&pin_directory)),
            pin_directory,
            pin_picker: None,
            scroll,
            today,
        }
    }

    fn calendar(&mut self, cx: &mut Context<Self>) {
        self.entry = None;
        self.saved_days = Rc::new(calendar::saved_days(&self.directory));
        self.today = Local::now().date_naive();
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

        let header = div()
            .h(px(HEADER_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .justify_between()
            .gap(px(12.))
            .px(px(16.))
            .border_b_1()
            .border_color(rgb(calendar_view::RULE));

        let root = div()
            .size_full()
            .relative()
            .flex()
            .flex_col()
            .pt(px(top))
            .pb(px(bottom))
            .pl(px(left))
            .pr(px(right))
            .bg(rgb(calendar_view::PAPER))
            .text_color(rgb(calendar_view::TEXT));

        if let Some(entry) = &mut self.entry {
            let selected_date = entry.date;
            let date = entry.date.format("%a, %b %-d, %Y").to_string();
            return root
                .child(
                    header
                        .child(
                            header_button("calendar", "‹ Calendar")
                                .on_click(cx.listener(|diary, _, _, cx| diary.calendar(cx))),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .truncate()
                                .text_center()
                                .text_sm()
                                .font_weight(gpui::FontWeight::SEMIBOLD)
                                .child(date),
                        )
                        .child(
                            header_button("clear-day", "Clear day")
                                .text_color(rgb(0x9b3a32))
                                .on_click(move |_, _, _| confirm_clear_day(selected_date)),
                        ),
                )
                .child(entry.ink.render().w_full().flex_1().min_h_0());
        }

        let diary = cx.entity().downgrade();
        root.bg(rgb(calendar_view::PAPER))
            .child(
                header
                    .child(
                        div()
                            .relative()
                            .font_family("Noto Serif")
                            .text_size(px(25.))
                            .child("Diary")
                            .child(
                                calendar_view::brush_rule(calendar_view::TEXT)
                                    .absolute()
                                    .bottom(px(-3.))
                                    .left_0(),
                            ),
                    )
                    .child(header_button("today", "Today").on_click(cx.listener(
                        |diary, _, _, cx| {
                            diary.today = Local::now().date_naive();
                            diary.scroll.scroll_to_item_strict(
                                calendar::month_index(diary.today),
                                ScrollStrategy::Center,
                            );
                            cx.notify();
                        },
                    ))),
            )
            .child(calendar_view::months(
                self.today,
                self.saved_days.clone(),
                self.pins.clone(),
                &self.scroll,
                window.viewport_size().height - px(top + bottom + HEADER_HEIGHT),
                move |date, action, _, app| {
                    let _ = diary.update(app, |diary, cx| {
                        match action {
                            DateAction::Open => {
                                diary.entry = Some(DaySheet {
                                    date,
                                    ink: ManagedPlatformView::new(INK_VIEW)
                                        .with_param("date", date.to_string()),
                                });
                            }
                            DateAction::PickPin => diary.pin_picker = Some((date, false)),
                        }
                        cx.notify();
                    });
                },
            ))
            .when_some(self.pin_picker, |root, (date, failed)| {
                let current = self.pins.get(&date).copied();
                root.child(calendar_view::pin_picker(
                    date,
                    current,
                    failed,
                    cx.listener(move |diary, action: &PinAction, _, cx| {
                        diary.update_pin(date, *action, cx);
                    }),
                ))
            })
    }
}

fn header_button(id: &'static str, label: &'static str) -> gpui::Stateful<gpui::Div> {
    div()
        .id(id)
        .focusable()
        .h(px(44.))
        .px(px(10.))
        .flex_shrink_0()
        .flex()
        .items_center()
        .rounded_sm()
        .text_color(rgb(calendar_view::ACCENT))
        .font_weight(gpui::FontWeight::SEMIBOLD)
        .cursor_pointer()
        .active(|style| style.bg(rgb(calendar_view::WASH)))
        .focus_visible(|style| style.bg(rgb(calendar_view::WASH)))
        .child(label)
}

fn confirm_clear_day(date: NaiveDate) {
    let _ = android_jni::with_env(|env| {
        let class = android_jni::find_app_class(env, "dev.gpui.mobile.GpuiPlatformView")?;
        let date = env
            .new_string(date.to_string())
            .map_err(|error| error.to_string())?;
        let result = env.call_static_method(
            &class,
            jni_str!("confirmClearDay"),
            jni_sig!("(Ljava/lang/String;)V"),
            &[JValue::Object(date.as_ref())],
        );
        if result.is_err() {
            env.exception_clear();
        }
        result.map(|_| ()).map_err(|error| error.to_string())
    });
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
        app.open_window(WindowOptions::default(), move |_, app| {
            app.new(|_| Diary::new(directory))
        })
        .expect("open diary window");
    });
}
