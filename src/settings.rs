use std::{
    fs::{self, File},
    io::{self, Write},
    path::Path,
};

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum ThemeMode {
    #[default]
    Auto,
    Light,
    Dark,
}

impl ThemeMode {
    #[cfg(target_os = "android")]
    pub const ALL: [Self; 3] = [Self::Auto, Self::Light, Self::Dark];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::Light => "light",
            Self::Dark => "dark",
        }
    }

    pub fn from_str(value: &str) -> Option<Self> {
        match value.trim() {
            "auto" => Some(Self::Auto),
            "light" => Some(Self::Light),
            "dark" => Some(Self::Dark),
            _ => None,
        }
    }

    #[cfg(target_os = "android")]
    pub fn code(self) -> i32 {
        match self {
            Self::Auto => 0,
            Self::Light => 1,
            Self::Dark => 2,
        }
    }

    #[cfg(target_os = "android")]
    pub fn label(self) -> &'static str {
        match self {
            Self::Auto => "Auto",
            Self::Light => "Light",
            Self::Dark => "Dark",
        }
    }

    #[cfg(target_os = "android")]
    pub fn description(self) -> &'static str {
        match self {
            Self::Auto => "Follow system appearance",
            Self::Light => "Warm paper light theme",
            Self::Dark => "Night ink dark theme",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum ToolbarMode {
    #[default]
    IconOnly,
    IconAndLabel,
    LabelOnly,
}

impl ToolbarMode {
    #[cfg(target_os = "android")]
    pub const ALL: [Self; 3] = [Self::IconOnly, Self::IconAndLabel, Self::LabelOnly];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::IconOnly => "icon_only",
            Self::IconAndLabel => "icon_and_label",
            Self::LabelOnly => "label_only",
        }
    }

    pub fn from_str(value: &str) -> Option<Self> {
        match value.trim() {
            "icon_only" => Some(Self::IconOnly),
            "icon_and_label" => Some(Self::IconAndLabel),
            "label_only" => Some(Self::LabelOnly),
            _ => None,
        }
    }

    #[cfg(target_os = "android")]
    pub fn code(self) -> i32 {
        match self {
            Self::IconOnly => 0,
            Self::IconAndLabel => 1,
            Self::LabelOnly => 2,
        }
    }

    #[cfg(target_os = "android")]
    pub fn label(self) -> &'static str {
        match self {
            Self::IconOnly => "Icon only",
            Self::IconAndLabel => "Icon + label",
            Self::LabelOnly => "Label only",
        }
    }

    #[cfg(target_os = "android")]
    pub fn description(self) -> &'static str {
        match self {
            Self::IconOnly => "Compact icons with active tool color",
            Self::IconAndLabel => "Extended toolbar with icons and text",
            Self::LabelOnly => "Text labels without tool icons",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum DateFormat {
    #[default]
    System,
    MonDayYear,
    DayMonYear,
    Iso,
    SlashDmy,
    SlashMdy,
    Cjk,
}

impl DateFormat {
    #[cfg(target_os = "android")]
    pub const ALL: [Self; 7] = [
        Self::System,
        Self::MonDayYear,
        Self::DayMonYear,
        Self::Iso,
        Self::SlashDmy,
        Self::SlashMdy,
        Self::Cjk,
    ];

    pub fn as_str(self) -> &'static str {
        match self {
            Self::System => "system",
            Self::MonDayYear => "mon_day_year",
            Self::DayMonYear => "day_mon_year",
            Self::Iso => "iso",
            Self::SlashDmy => "slash_dmy",
            Self::SlashMdy => "slash_mdy",
            Self::Cjk => "cjk",
        }
    }

    pub fn from_str(value: &str) -> Option<Self> {
        match value.trim() {
            "system" => Some(Self::System),
            "mon_day_year" => Some(Self::MonDayYear),
            "day_mon_year" => Some(Self::DayMonYear),
            "iso" => Some(Self::Iso),
            "slash_dmy" => Some(Self::SlashDmy),
            "slash_mdy" => Some(Self::SlashMdy),
            "cjk" => Some(Self::Cjk),
            _ => None,
        }
    }

    pub fn format_custom(self, date: chrono::NaiveDate) -> Option<String> {
        match self {
            Self::System => None,
            Self::MonDayYear => Some(date.format("%a, %b %-d, %Y").to_string()),
            Self::DayMonYear => Some(date.format("%a, %-d %b %Y").to_string()),
            Self::Iso => Some(date.format("%Y-%m-%d").to_string()),
            Self::SlashDmy => Some(date.format("%d/%m/%Y").to_string()),
            Self::SlashMdy => Some(date.format("%m/%d/%Y").to_string()),
            Self::Cjk => Some(date.format("%Y年%-m月%-d日").to_string()),
        }
    }

    #[cfg(target_os = "android")]
    pub fn label(self) -> &'static str {
        match self {
            Self::System => "System",
            Self::MonDayYear => "Thu, Nov 26, 2026",
            Self::DayMonYear => "Thu, 26 Nov 2026",
            Self::Iso => "2026-11-26",
            Self::SlashDmy => "26/11/2026",
            Self::SlashMdy => "11/26/2026",
            Self::Cjk => "2026年11月26日",
        }
    }

    #[cfg(target_os = "android")]
    pub fn description(self) -> &'static str {
        match self {
            Self::System => "Follow your device locale date format",
            Self::MonDayYear => "Weekday, month, day, year",
            Self::DayMonYear => "Weekday, day, month, year",
            Self::Iso => "ISO 8601 (YYYY-MM-DD)",
            Self::SlashDmy => "Day / Month / Year",
            Self::SlashMdy => "Month / Day / Year",
            Self::Cjk => "East Asian year-month-day",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum DiaryPlugin {
    Hanzi,
    Contour3d,
}

impl DiaryPlugin {
    #[cfg(target_os = "android")]
    pub const ALL: [Self; 2] = [Self::Hanzi, Self::Contour3d];

    pub const BIT_HANZI: i32 = 1 << 0;
    pub const BIT_CONTOUR_3D: i32 = 1 << 1;

    #[cfg(target_os = "android")]
    pub fn id(self) -> &'static str {
        match self {
            Self::Hanzi => "plugin_hanzi",
            Self::Contour3d => "plugin_contour",
        }
    }

    #[cfg(target_os = "android")]
    pub fn label(self) -> &'static str {
        match self {
            Self::Hanzi => "Chinese word everyday",
            Self::Contour3d => "Contour line 3D",
        }
    }

    #[cfg(target_os = "android")]
    pub fn description(self) -> &'static str {
        match self {
            Self::Hanzi => "Load daily Hanzi stroke-practice cards in the day toolbox",
            Self::Contour3d => "Draw 2D contour maps and render them as smooth or block 3D terrain",
        }
    }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct AppSettings {
    pub theme: ThemeMode,
    pub toolbar: ToolbarMode,
    pub date_format: DateFormat,
    pub plugin_hanzi: bool,
    pub plugin_contour: bool,
}

impl AppSettings {
    pub fn is_plugin_enabled(self, plugin: DiaryPlugin) -> bool {
        match plugin {
            DiaryPlugin::Hanzi => self.plugin_hanzi,
            DiaryPlugin::Contour3d => self.plugin_contour,
        }
    }

    pub fn toggle_plugin(&mut self, plugin: DiaryPlugin) {
        match plugin {
            DiaryPlugin::Hanzi => self.plugin_hanzi = !self.plugin_hanzi,
            DiaryPlugin::Contour3d => self.plugin_contour = !self.plugin_contour,
        }
    }

    pub fn plugins_mask(self) -> i32 {
        let mut mask = 0;
        if self.plugin_hanzi {
            mask |= DiaryPlugin::BIT_HANZI;
        }
        if self.plugin_contour {
            mask |= DiaryPlugin::BIT_CONTOUR_3D;
        }
        mask
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Palette {
    pub text: u32,
    pub muted: u32,
    pub accent: u32,
    pub paper: u32,
    pub rule: u32,
    pub wash: u32,
    pub danger: u32,
    pub scrim: u32,
}

impl Palette {
    pub const LIGHT: Self = Self {
        text: 0x302e29,
        muted: 0x736c60,
        accent: 0x984631,
        paper: 0xf8f3e8,
        rule: 0xe0d7c6,
        wash: 0xeee5d5,
        danger: 0x9b3a32,
        scrim: 0x262b2a55,
    };

    pub const DARK: Self = Self {
        text: 0xebe4d8,
        muted: 0xa59c8e,
        accent: 0xd97757,
        paper: 0x1b1917,
        rule: 0x3d3731,
        wash: 0x2a2622,
        danger: 0xe06c60,
        scrim: 0x00000088,
    };

    pub fn resolve(theme: ThemeMode, system_dark: bool) -> Self {
        let dark = match theme {
            ThemeMode::Auto => system_dark,
            ThemeMode::Light => false,
            ThemeMode::Dark => true,
        };
        if dark { Self::DARK } else { Self::LIGHT }
    }

    #[cfg(target_os = "android")]
    pub fn to_argb_array(self) -> [i32; 7] {
        let opaque = |rgb: u32| (0xff00_0000u32 | rgb) as i32;
        [
            opaque(self.paper),
            opaque(self.wash),
            opaque(self.rule),
            opaque(self.text),
            opaque(self.muted),
            opaque(self.accent),
            opaque(self.danger),
        ]
    }
}

pub fn load(path: &Path) -> AppSettings {
    let Ok(contents) = fs::read_to_string(path) else {
        return AppSettings::default();
    };
    let mut settings = AppSettings::default();
    for line in contents.lines() {
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "theme" => {
                if let Some(theme) = ThemeMode::from_str(value) {
                    settings.theme = theme;
                }
            }
            "toolbar" => {
                if let Some(toolbar) = ToolbarMode::from_str(value) {
                    settings.toolbar = toolbar;
                }
            }
            "date_format" => {
                if let Some(date_format) = DateFormat::from_str(value) {
                    settings.date_format = date_format;
                }
            }
            "plugin_hanzi" => {
                if let Some(enabled) = parse_bool(value) {
                    settings.plugin_hanzi = enabled;
                }
            }
            "plugin_contour" => {
                if let Some(enabled) = parse_bool(value) {
                    settings.plugin_contour = enabled;
                }
            }
            _ => {}
        }
    }
    settings
}

fn parse_bool(value: &str) -> Option<bool> {
    match value.trim() {
        "true" | "1" => Some(true),
        "false" | "0" => Some(false),
        _ => None,
    }
}

pub fn save(path: &Path, settings: AppSettings) -> io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let staged = path.with_extension("tmp");
    let result = (|| {
        let mut file = File::create(&staged)?;
        writeln!(file, "theme={}", settings.theme.as_str())?;
        writeln!(file, "toolbar={}", settings.toolbar.as_str())?;
        writeln!(file, "date_format={}", settings.date_format.as_str())?;
        writeln!(file, "plugin_hanzi={}", settings.plugin_hanzi)?;
        writeln!(file, "plugin_contour={}", settings.plugin_contour)?;
        file.sync_all()?;
        fs::rename(&staged, path)
    })();
    if result.is_err() {
        let _ = fs::remove_file(staged);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_settings_and_defaults_on_missing_file() {
        let path = std::env::temp_dir().join(format!("and-diary-settings-{}.txt", std::process::id()));
        let _ = fs::remove_file(&path);

        assert_eq!(load(&path), AppSettings::default());

        let updated = AppSettings {
            theme: ThemeMode::Dark,
            toolbar: ToolbarMode::IconAndLabel,
            date_format: DateFormat::Iso,
            plugin_hanzi: true,
            plugin_contour: false,
        };
        save(&path, updated).unwrap();
        assert_eq!(load(&path), updated);

        let updated = AppSettings {
            theme: ThemeMode::Light,
            toolbar: ToolbarMode::LabelOnly,
            date_format: DateFormat::Cjk,
            plugin_hanzi: false,
            plugin_contour: true,
        };
        save(&path, updated).unwrap();
        assert_eq!(load(&path), updated);

        fs::remove_file(&path).unwrap();
    }

    #[test]
    fn toggles_plugins_and_encodes_bitmask() {
        let mut settings = AppSettings::default();
        assert!(!settings.is_plugin_enabled(DiaryPlugin::Hanzi));
        assert!(!settings.is_plugin_enabled(DiaryPlugin::Contour3d));
        assert_eq!(settings.plugins_mask(), 0);

        settings.toggle_plugin(DiaryPlugin::Hanzi);
        assert!(settings.is_plugin_enabled(DiaryPlugin::Hanzi));
        assert_eq!(settings.plugins_mask(), DiaryPlugin::BIT_HANZI);

        settings.toggle_plugin(DiaryPlugin::Contour3d);
        assert_eq!(
            settings.plugins_mask(),
            DiaryPlugin::BIT_HANZI | DiaryPlugin::BIT_CONTOUR_3D
        );

        settings.toggle_plugin(DiaryPlugin::Hanzi);
        assert_eq!(settings.plugins_mask(), DiaryPlugin::BIT_CONTOUR_3D);
    }

    #[test]
    fn resolves_palette_from_theme_mode() {
        assert_eq!(Palette::resolve(ThemeMode::Light, true), Palette::LIGHT);
        assert_eq!(Palette::resolve(ThemeMode::Dark, false), Palette::DARK);
        assert_eq!(Palette::resolve(ThemeMode::Auto, false), Palette::LIGHT);
        assert_eq!(Palette::resolve(ThemeMode::Auto, true), Palette::DARK);
    }

    #[test]
    fn formats_custom_dates_and_leaves_system_to_platform() {
        let date = chrono::NaiveDate::from_ymd_opt(2026, 11, 26).unwrap();
        assert_eq!(DateFormat::System.format_custom(date), None);
        assert_eq!(
            DateFormat::Iso.format_custom(date).as_deref(),
            Some("2026-11-26")
        );
        assert_eq!(
            DateFormat::SlashDmy.format_custom(date).as_deref(),
            Some("26/11/2026")
        );
        assert_eq!(
            DateFormat::SlashMdy.format_custom(date).as_deref(),
            Some("11/26/2026")
        );
        assert_eq!(
            DateFormat::Cjk.format_custom(date).as_deref(),
            Some("2026年11月26日")
        );
    }
}
