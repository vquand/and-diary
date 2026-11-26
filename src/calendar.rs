use chrono::{Datelike, NaiveDate};
use std::{collections::BTreeSet, fs, path::Path};

const FIRST_YEAR: i32 = 1900;
pub const MONTH_COUNT: usize = 300 * 12;

pub fn month_at(index: usize) -> NaiveDate {
    NaiveDate::from_ymd_opt(FIRST_YEAR + (index / 12) as i32, (index % 12 + 1) as u32, 1)
        .expect("valid calendar month")
}

pub fn month_index(date: NaiveDate) -> usize {
    ((date.year() - FIRST_YEAR) * 12 + date.month0() as i32).clamp(0, MONTH_COUNT as i32 - 1)
        as usize
}

pub fn month_days(month: NaiveDate) -> [Option<NaiveDate>; 42] {
    let first_column = month.weekday().num_days_from_monday() as usize;
    std::array::from_fn(|index| month.with_day(index.checked_sub(first_column)? as u32 + 1))
}

pub fn saved_days(directory: &Path) -> BTreeSet<NaiveDate> {
    fs::read_dir(directory)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
        .filter_map(|entry| entry_date(&entry.file_name().to_string_lossy()))
        .collect()
}

pub fn completed_hanzi_days(directory: &Path) -> BTreeSet<NaiveDate> {
    fs::read_dir(directory)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter(|entry| entry.file_type().is_ok_and(|kind| kind.is_file()))
        .filter_map(|entry| {
            let name = entry.file_name();
            let date = name.to_string_lossy().strip_suffix(".items")?.to_string();
            let parsed = NaiveDate::parse_from_str(&date, "%Y-%m-%d").ok()?;
            if parsed.to_string() != date {
                return None;
            }
            let content = fs::read_to_string(entry.path()).ok()?;
            has_completed_hanzi(&content).then_some(parsed)
        })
        .collect()
}

fn has_completed_hanzi(json: &str) -> bool {
    let mut in_string = false;
    let mut escaped = false;
    let mut obj_start: Option<usize> = None;

    for (idx, ch) in json.char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            continue;
        }
        match ch {
            '"' => in_string = true,
            '{' => obj_start = Some(idx + 1),
            '}' => {
                if let Some(start) = obj_start.take() {
                    let body = &json[start..idx];
                    if object_has_completed_hanzi(body) {
                        return true;
                    }
                }
            }
            _ => {}
        }
    }
    false
}

fn object_has_completed_hanzi(body: &str) -> bool {
    if !body.contains("\"kind\":\"hanzi\"") && !body.contains("\"kind\": \"hanzi\"") {
        return false;
    }
    let Some((_, rest)) = body.split_once("\"score\"") else {
        return false;
    };
    let digits: String = rest
        .trim_start()
        .strip_prefix(':')
        .unwrap_or("")
        .trim_start()
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    digits.parse::<u32>().is_ok_and(|score| score > 0)
}

fn entry_date(filename: &str) -> Option<NaiveDate> {
    let date = filename
        .strip_suffix(".ink")
        .or_else(|| filename.strip_suffix(".items"))?;
    let parsed = NaiveDate::parse_from_str(date, "%Y-%m-%d").ok()?;
    (parsed.to_string() == date).then_some(parsed)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lays_out_leap_days_and_six_week_months() {
        let february = NaiveDate::from_ymd_opt(2024, 2, 1).unwrap();
        let days = month_days(february);
        assert_eq!(days[3], Some(february));
        assert_eq!(days[31], february.with_day(29));
        assert_eq!(days.iter().flatten().count(), 29);

        let november = NaiveDate::from_ymd_opt(2026, 11, 1).unwrap();
        assert_eq!(month_days(november)[35], november.with_day(30));
        assert_eq!(
            month_at(month_index(november) + 2),
            NaiveDate::from_ymd_opt(2027, 1, 1).unwrap()
        );
    }

    #[test]
    fn marks_only_committed_entry_names() {
        assert!(entry_date("2024-02-29.ink").is_some());
        assert!(entry_date("2024-02-29.items").is_some());
        assert!(entry_date("2024-02-29.ink.new").is_none());
        assert!(entry_date("2025-02-29.ink").is_none());
        assert!(entry_date("2024-2-9.ink").is_none());
        assert!(saved_days(Path::new("/dev/null")).is_empty());
    }

    #[test]
    fn detects_completed_hanzi_in_items_json() {
        assert!(!has_completed_hanzi(r#"[{"kind":"hanzi","ref":"永","score":0}]"#));
        assert!(!has_completed_hanzi(r#"[{"kind":"sticker","ref":"star","score":95}]"#));
        assert!(has_completed_hanzi(
            r#"[{"kind":"sticker","ref":"star","score":0},{"kind":"hanzi","ref":"永","score":92}]"#
        ));
        // Braces inside quoted labels must not truncate the JSON object scan.
        assert!(has_completed_hanzi(
            r#"[{"kind":"hanzi","label":"note}1\"x","ref":"永","score":88}]"#
        ));

        let dir = std::env::temp_dir().join(format!("and-diary-hanzi-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(
            dir.join("2026-11-26.items"),
            r#"[{"kind":"hanzi","ref":"永","score":91}]"#,
        )
        .unwrap();
        fs::write(
            dir.join("2026-11-25.items"),
            r#"[{"kind":"hanzi","ref":"心","score":0}]"#,
        )
        .unwrap();
        let done = completed_hanzi_days(&dir);
        assert!(done.contains(&NaiveDate::from_ymd_opt(2026, 11, 26).unwrap()));
        assert!(!done.contains(&NaiveDate::from_ymd_opt(2026, 11, 25).unwrap()));
        fs::remove_dir_all(&dir).unwrap();
    }
}
