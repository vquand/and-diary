use crate::settings::{AppSettings, DateFormat, DiaryPlugin, Palette, ThemeMode, ToolbarMode};
use gpui::{App, FontWeight, IntoElement, Window, div, prelude::*, px, rgb};
use std::rc::Rc;

#[derive(Clone, Copy)]
pub enum SettingsAction {
    SetTheme(ThemeMode),
    SetToolbar(ToolbarMode),
    SetDateFormat(DateFormat),
    TogglePlugin(DiaryPlugin),
}

pub fn settings_page(
    current: AppSettings,
    palette: Palette,
    on_action: impl Fn(SettingsAction, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let on_action = Rc::new(on_action);

    let theme_options = ThemeMode::ALL.map(|mode| {
        let on_action = on_action.clone();
        let selected = current.theme == mode;
        option_card(
            mode.as_str(),
            mode.label(),
            mode.description(),
            selected,
            palette,
            move |window, app| on_action(SettingsAction::SetTheme(mode), window, app),
        )
    });

    let toolbar_options = ToolbarMode::ALL.map(|mode| {
        let on_action = on_action.clone();
        let selected = current.toolbar == mode;
        option_card(
            mode.as_str(),
            mode.label(),
            mode.description(),
            selected,
            palette,
            move |window, app| on_action(SettingsAction::SetToolbar(mode), window, app),
        )
    });

    let date_format_options = DateFormat::ALL.map(|mode| {
        let on_action = on_action.clone();
        let selected = current.date_format == mode;
        option_card(
            mode.as_str(),
            mode.label(),
            mode.description(),
            selected,
            palette,
            move |window, app| on_action(SettingsAction::SetDateFormat(mode), window, app),
        )
    });

    let plugin_options = DiaryPlugin::ALL.map(|plugin| {
        let on_action = on_action.clone();
        let enabled = current.is_plugin_enabled(plugin);
        toggle_card(
            plugin.id(),
            plugin.label(),
            plugin.description(),
            enabled,
            palette,
            move |window, app| on_action(SettingsAction::TogglePlugin(plugin), window, app),
        )
    });

    div()
        .id("settings-scroll")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .px(px(16.))
        .py(px(18.))
        .child(
            div()
                .mx_auto()
                .w_full()
                .max_w(px(632.))
                .flex()
                .flex_col()
                .gap(px(24.))
                .child(section(
                    "Plugins",
                    "Optional tools that load into the day toolbox only when enabled.",
                    palette,
                    plugin_options,
                ))
                .child(section(
                    "Theme",
                    "Choose light, dark, or match your device setting.",
                    palette,
                    theme_options,
                ))
                .child(section(
                    "Day toolbox",
                    "Choose whether tools show icons, labels, or both.",
                    palette,
                    toolbar_options,
                ))
                .child(section(
                    "Date format",
                    "Choose how dates appear on a day's diary header, or follow System.",
                    palette,
                    date_format_options,
                )),
        )
}

fn section<const N: usize>(
    title: &'static str,
    subtitle: &'static str,
    palette: Palette,
    options: [impl IntoElement; N],
) -> impl IntoElement {
    div()
        .flex()
        .flex_col()
        .gap(px(10.))
        .child(
            div()
                .flex()
                .flex_col()
                .gap(px(2.))
                .child(
                    div()
                        .font_family("Noto Serif")
                        .text_size(px(22.))
                        .text_color(rgb(palette.text))
                        .child(title),
                )
                .child(
                    div()
                        .text_sm()
                        .text_color(rgb(palette.muted))
                        .child(subtitle),
                ),
        )
        .child(div().flex().flex_col().gap(px(8.)).children(options))
}

fn setting_card(
    id: &'static str,
    label: &'static str,
    description: &'static str,
    active: bool,
    palette: Palette,
    trailing: impl IntoElement,
    on_click: impl Fn(&mut Window, &mut App) + 'static,
) -> impl IntoElement {
    div()
        .id(id)
        .focusable()
        .w_full()
        .min_h(px(56.))
        .px(px(14.))
        .py(px(12.))
        .rounded_md()
        .border_1()
        .border_color(rgb(if active { palette.accent } else { palette.rule }))
        .bg(rgb(if active { palette.wash } else { palette.paper }))
        .flex()
        .items_center()
        .justify_between()
        .gap(px(12.))
        .cursor_pointer()
        .active(move |style| style.bg(rgb(palette.wash)))
        .focus_visible(move |style| style.bg(rgb(palette.wash)))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(2.))
                .child(
                    div()
                        .text_size(px(15.))
                        .font_weight(if active {
                            FontWeight::SEMIBOLD
                        } else {
                            FontWeight::NORMAL
                        })
                        .text_color(rgb(if active { palette.accent } else { palette.text }))
                        .child(label),
                )
                .child(
                    div()
                        .text_size(px(12.))
                        .text_color(rgb(palette.muted))
                        .child(description),
                ),
        )
        .child(trailing)
        .on_click(move |_, window, app| on_click(window, app))
}

fn option_card(
    id: &'static str,
    label: &'static str,
    description: &'static str,
    selected: bool,
    palette: Palette,
    on_select: impl Fn(&mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let radio = div()
        .size(px(20.))
        .flex_shrink_0()
        .rounded_full()
        .border_2()
        .border_color(rgb(if selected { palette.accent } else { palette.rule }))
        .flex()
        .items_center()
        .justify_center()
        .when(selected, |radio| {
            radio.child(div().size(px(10.)).rounded_full().bg(rgb(palette.accent)))
        });
    setting_card(id, label, description, selected, palette, radio, on_select)
}

fn toggle_card(
    id: &'static str,
    label: &'static str,
    description: &'static str,
    enabled: bool,
    palette: Palette,
    on_toggle: impl Fn(&mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let switch = div()
        .w(px(40.))
        .h(px(22.))
        .flex_shrink_0()
        .rounded_full()
        .border_1()
        .border_color(rgb(if enabled { palette.accent } else { palette.rule }))
        .bg(rgb(if enabled { palette.accent } else { palette.paper }))
        .px(px(2.))
        .flex()
        .items_center()
        .when(enabled, |track| track.justify_end())
        .when(!enabled, |track| track.justify_start())
        .child(
            div()
                .size(px(16.))
                .rounded_full()
                .bg(rgb(if enabled { palette.paper } else { palette.muted })),
        );
    setting_card(id, label, description, enabled, palette, switch, on_toggle)
}
