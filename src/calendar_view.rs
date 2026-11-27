use crate::{
    calendar::{MONTH_COUNT, month_at, month_days},
    pins::Pin,
    settings::Palette,
    stickers::{hanzi_badge, symbol as pin_symbol},
};
use chrono::{Datelike, NaiveDate};
use gpui::{
    AnyElement, App, Div, FontWeight, HitboxBehavior, LongPressEvent, PathBuilder, Pixels,
    ScrollStrategy, TouchPhase, UniformListScrollHandle, Window, canvas, div, linear_color_stop,
    linear_gradient, point, prelude::*, px, rgb, rgba, uniform_list,
};
use std::{
    collections::{BTreeMap, BTreeSet},
    rc::Rc,
};

const WEEKDAYS: [&str; 7] = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const MONTH_LABEL_HEIGHT: f32 = 105.;

pub enum DateAction {
    Open,
    PickPin,
}

#[derive(Clone, Copy)]
pub enum PinAction {
    Set(Option<Pin>),
    Close,
}

type DateHandler = dyn Fn(NaiveDate, DateAction, &mut Window, &mut App);

pub fn months(
    today: NaiveDate,
    entries: Rc<BTreeSet<NaiveDate>>,
    hanzi_days: Rc<BTreeSet<NaiveDate>>,
    pins: Rc<BTreeMap<NaiveDate, Pin>>,
    palette: Palette,
    scroll: &UniformListScrollHandle,
    viewport_height: Pixels,
    activate_date: impl Fn(NaiveDate, DateAction, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let centered = {
        let state = scroll.0.borrow();
        state.last_item_size.and_then(|previous| {
            if state.deferred_scroll_to_item.is_some()
                || (previous.item.height - viewport_height).abs() <= px(1.)
            {
                return None;
            }
            let center = previous.item.height / 2. - state.base_handle.offset().y;
            let old_month_height = previous.contents.height.as_f32() / MONTH_COUNT as f32;
            Some(((center.as_f32() / old_month_height) as usize).min(MONTH_COUNT - 1))
        })
    };
    if let Some(index) = centered {
        scroll.scroll_to_item_strict(index, ScrollStrategy::Center);
    }

    let weeks_height = (viewport_height * 0.7 - px(MONTH_LABEL_HEIGHT)).max(px(48. * 6.));
    let activate_date: Rc<DateHandler> = Rc::new(activate_date);
    let list = uniform_list("months", MONTH_COUNT, move |range, _, _| {
        range
            .map(|index| {
                month_grid(
                    month_at(index),
                    today,
                    &entries,
                    &hanzi_days,
                    &pins,
                    palette,
                    weeks_height,
                    activate_date.clone(),
                )
            })
            .collect::<Vec<_>>()
    })
    .track_scroll(scroll)
    .w_full()
    .flex_1()
    .min_h_0();

    let opaque = linear_color_stop(rgb(palette.paper), 0.);
    let transparent = linear_color_stop(rgb(palette.paper).opacity(0.), 1.);
    let fade = |angle| {
        canvas(|_, _, _| (), |_, _, _, _| {})
            .absolute()
            .left_0()
            .w_full()
            .h(px(18.))
            .bg(linear_gradient(angle, opaque, transparent))
    };
    div()
        .relative()
        .flex()
        .flex_col()
        .flex_1()
        .min_h_0()
        .child(list)
        .child(fade(180.).top_0())
        .child(fade(0.).bottom_0())
}

fn month_grid(
    month: NaiveDate,
    today: NaiveDate,
    entries: &BTreeSet<NaiveDate>,
    hanzi_days: &BTreeSet<NaiveDate>,
    pins: &BTreeMap<NaiveDate, Pin>,
    palette: Palette,
    weeks_height: Pixels,
    activate_date: Rc<DateHandler>,
) -> Div {
    let weekdays = div()
        .h(px(24.))
        .flex()
        .items_center()
        .children(WEEKDAYS.map(|day| {
            div()
                .flex_1()
                .text_center()
                .text_sm()
                .text_color(rgb(palette.muted))
                .child(day)
        }));
    let days = month_days(month);
    let week_count = days
        .iter()
        .rposition(Option::is_some)
        .expect("month has dates")
        / 7
        + 1;
    let row_height = weeks_height / week_count as f32;
    let weeks = days.chunks_exact(7).take(week_count).map(|week| {
        div()
            .relative()
            .flex()
            .children(week.iter().map(|day| match day {
                Some(day) => date_cell(
                    *day,
                    *day == today,
                    entries.contains(day),
                    hanzi_days.contains(day),
                    pins.get(day).copied(),
                    palette,
                    row_height,
                    activate_date.clone(),
                ),
                None => div().flex_1().h(row_height).into_any_element(),
            }))
    });

    div().w_full().px(px(16.)).child(
        div()
            .relative()
            .mx_auto()
            .w_full()
            .max_w(px(632.))
            .pt(px(16.))
            .pb(px(16.))
            .border_b_1()
            .border_color(rgb(palette.rule))
            .child(
                div()
                    .h(px(48.))
                    .flex()
                    .items_center()
                    .justify_between()
                    .text_color(rgb(palette.text))
                    .child(
                        div()
                            .font_family("Noto Serif")
                            .text_size(px(30.))
                            .child(month.format("%B").to_string()),
                    )
                    .child(
                        div()
                            .text_sm()
                            .text_color(rgb(palette.accent))
                            .border_1()
                            .border_color(rgb(palette.accent).opacity(0.55))
                            .px(px(6.))
                            .py(px(2.))
                            .child(month.format("%Y").to_string()),
                    ),
            )
            .child(weekdays)
            .child(
                div()
                    .relative()
                    .children(weeks)
                    .child(ink_grid(week_count, palette.text)),
            )
            .child(
                brush_rule(palette.text)
                    .absolute()
                    .top(px(59.))
                    .left_0()
                    .w(px(52.)),
            ),
    )
}

fn date_cell(
    date: NaiveDate,
    is_today: bool,
    has_entry: bool,
    hanzi_done: bool,
    pin: Option<Pin>,
    palette: Palette,
    height: Pixels,
    activate_date: Rc<DateHandler>,
) -> AnyElement {
    let date_id = date.to_string();
    let hold_date = activate_date.clone();
    let context_date = activate_date.clone();
    div()
        .id(date_id.clone())
        .accessibility_id(date_id)
        .focusable()
        .relative()
        .flex_1()
        .h(height)
        .flex()
        .items_center()
        .justify_center()
        .rounded_md()
        .cursor_pointer()
        .active(move |style| style.bg(rgb(palette.wash)))
        .focus_visible(move |style| style.bg(rgb(palette.wash)))
        .child(
            div()
                .relative()
                .flex()
                .items_center()
                .justify_center()
                .absolute()
                .left(px(3.))
                .bottom(px(6.))
                .size(px(30.))
                .font_family("Noto Serif")
                .text_size(px(16.))
                .when(is_today, |item| {
                    item.text_color(rgb(palette.accent))
                        .font_weight(FontWeight::SEMIBOLD)
                        .child(ink_ring(palette.accent))
                })
                .child(date.day().to_string())
                .when(has_entry, |item| {
                    item.child(
                        div()
                            .absolute()
                            .bottom(px(2.))
                            .size(px(5.))
                            .rounded_full()
                            .bg(rgb(palette.text)),
                    )
                }),
        )
        .when_some(pin, |cell, pin| {
            cell.child(pin_symbol(pin).absolute().top(px(4.)).right(px(4.)))
        })
        .when(hanzi_done, |cell| {
            cell.child(hanzi_badge().absolute().bottom(px(6.)).right(px(4.)))
        })
        .child(
            canvas(
                |bounds, window, _| window.insert_hitbox(bounds, HitboxBehavior::Normal),
                move |_, hitbox, window, _| {
                    window.on_mouse_event(move |event: &LongPressEvent, phase, window, app| {
                        if phase.bubble()
                            && event.phase == TouchPhase::Started
                            && hitbox.is_hovered(window)
                            && !window.default_prevented()
                        {
                            window.prevent_default();
                            app.stop_propagation();
                            hold_date(date, DateAction::PickPin, window, app);
                        }
                    });
                },
            )
            .absolute()
            .inset_0(),
        )
        .on_click(move |_, window, app| activate_date(date, DateAction::Open, window, app))
        .on_aux_click(move |event, window, app| {
            if event.is_secondary() {
                context_date(date, DateAction::PickPin, window, app);
            }
        })
        .into_any_element()
}

fn ink_grid(weeks: usize, color: u32) -> impl IntoElement {
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let at = |x, y| bounds.origin + point(bounds.size.width * x, bounds.size.height * y);
            let mut lines = PathBuilder::fill();
            // Fixed phases keep the brush marks still while scrolling and repainting.
            let mut stroke = |start: gpui::Point<Pixels>, end: gpui::Point<Pixels>, phase: f32| {
                let delta = end - start;
                let length = (delta.x.as_f32().powi(2) + delta.y.as_f32().powi(2)).sqrt();
                if length <= f32::EPSILON {
                    return;
                }
                let normal = point(-delta.y / length, delta.x / length);
                for side in [1., -1.] {
                    for step in 0..=64 {
                        let index = if side > 0. { step } else { 64 - step };
                        let t = index as f32 / 64.;
                        let travel = t * length;
                        let wobble = (travel / 31. + phase).sin() * 0.55
                            + (travel / 13. + phase * 2.).sin() * 0.18;
                        let pressure = 0.65 + 0.35 * (travel / 23. + phase).sin();
                        let taper = (t * 16.).min((1. - t) * 16.).min(1.);
                        let edge = wobble + side * (0.12 + pressure * taper);
                        let position = start + delta * t + normal * edge;
                        if step == 0 && side > 0. {
                            lines.move_to(position);
                        } else {
                            lines.line_to(position);
                        }
                    }
                }
                lines.close();
            };
            for column in 0..=7 {
                let x = column as f32 / 7.;
                stroke(at(x, 0.), at(x, 1.), column as f32 * 1.9);
            }
            for row in 0..=weeks {
                let y = row as f32 / weeks as f32;
                stroke(at(0., y), at(1., y), row as f32 * 2.3 + 0.7);
            }
            if let Ok(path) = lines.build() {
                window.paint_path(path, rgb(color).opacity(0.45));
            }
        },
    )
    .absolute()
    .inset_0()
}

pub fn brush_rule(color: u32) -> impl IntoElement + gpui::Styled {
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let at = |x, y| bounds.origin + point(bounds.size.width * x, bounds.size.height * y);
            let mut stroke = PathBuilder::fill();
            stroke.move_to(at(0., 0.5));
            stroke.curve_to(at(0.96, 0.25), at(0.35, 0.05));
            stroke.line_to(at(1., 0.5));
            stroke.curve_to(at(0.02, 0.8), at(0.55, 0.65));
            stroke.close();
            if let Ok(path) = stroke.build() {
                window.paint_path(path, rgb(color).opacity(0.8));
            }
        },
    )
    .h(px(5.))
    .w_full()
}

fn ink_ring(color: u32) -> impl IntoElement {
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let at = |x, y| bounds.origin + point(bounds.size.width * x, bounds.size.height * y);
            let mut ring = PathBuilder::stroke(px(1.7));
            ring.move_to(at(0.74, 0.11));
            ring.cubic_bezier_to(at(0.12, 0.59), at(0.31, -0.06), at(0.01, 0.23));
            ring.cubic_bezier_to(at(0.86, 0.72), at(0.19, 1.05), at(0.77, 1.04));
            ring.cubic_bezier_to(at(0.61, 0.08), at(1.05, 0.37), at(0.9, 0.07));
            if let Ok(path) = ring.build() {
                window.paint_path(path, rgb(color).opacity(0.85));
            }
        },
    )
    .absolute()
    .inset_0()
}

pub fn pin_picker(
    date: NaiveDate,
    current: Option<Pin>,
    failed: bool,
    palette: Palette,
    activate: impl Fn(&PinAction, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let activate = Rc::new(activate);
    let options = Pin::ALL.map(|pin| {
        let activate = activate.clone();
        let label = pin.label();
        div()
            .id(label)
            .focusable()
            .h(px(64.))
            .min_w_0()
            .text_size(px(12.))
            .px(px(3.))
            .flex()
            .items_center()
            .flex_col()
            .justify_center()
            .gap(px(4.))
            .rounded_sm()
            .cursor_pointer()
            .when(current == Some(pin), |row| row.bg(rgb(palette.wash)))
            .focus_visible(move |row| row.bg(rgb(palette.wash)))
            .active(move |row| row.bg(rgb(palette.wash)))
            .child(pin_symbol(pin))
            .child(label)
            .on_click(move |_, window, app| activate(&PinAction::Set(Some(pin)), window, app))
    });
    let remove_pin = activate.clone();

    div()
        .absolute()
        .inset_0()
        .occlude()
        .flex()
        .items_center()
        .justify_center()
        .p(px(16.))
        .bg(rgba(palette.scrim))
        .child(
            div()
                .id("pin-picker")
                .w_full()
                .max_w(px(320.))
                .max_h_full()
                .overflow_y_scroll()
                .p(px(16.))
                .rounded_sm()
                .border_1()
                .border_color(rgb(palette.rule))
                .bg(rgb(palette.paper))
                .child(
                    div()
                        .px(px(9.))
                        .pb(px(8.))
                        .font_family("Noto Serif")
                        .text_xl()
                        .child(date.format("Pin %b %-d").to_string()),
                )
                .child(div().grid().grid_cols(3).gap(px(6.)).children(options))
                .when(failed, |panel| {
                    panel.child(
                        div()
                            .px(px(9.))
                            .text_color(rgb(palette.danger))
                            .child("Could not save the pin."),
                    )
                })
                .child(
                    div()
                        .flex()
                        .justify_end()
                        .pt(px(6.))
                        .gap(px(8.))
                        .when(current.is_some(), |actions| {
                            actions.child(
                                picker_button("remove-pin", "Remove pin", palette).on_click(
                                    move |_, window, app| {
                                        remove_pin(&PinAction::Set(None), window, app)
                                    },
                                ),
                            )
                        })
                        .child(
                            picker_button("close-pin-picker", "Cancel", palette).on_click(
                                move |_, window, app| activate(&PinAction::Close, window, app),
                            ),
                        ),
                ),
        )
}

fn picker_button(id: &'static str, label: &'static str, palette: Palette) -> gpui::Stateful<Div> {
    div()
        .id(id)
        .focusable()
        .h(px(44.))
        .px(px(10.))
        .flex()
        .items_center()
        .rounded_md()
        .text_color(rgb(palette.accent))
        .cursor_pointer()
        .focus_visible(move |style| style.bg(rgb(palette.wash)))
        .active(move |style| style.bg(rgb(palette.wash)))
        .child(label)
}
