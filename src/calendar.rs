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
    let mut depth = 0usize;
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
            '{' => {
                depth += 1;
                if depth == 1 {
                    obj_start = Some(idx + 1);
                }
            }
            '}' => {
                if depth == 1
                    && let Some(start) = obj_start.take()
                {
                    let body = &json[start..idx];
                    if object_has_completed_hanzi(body) {
                        return true;
                    }
                }
                depth = depth.saturating_sub(1);
            }
            _ => {}
        }
    }
    false
}

/// Reads the remainder of a JSON string whose opening quote was already consumed.
fn read_json_string(chars: &mut std::iter::Peekable<std::str::Chars<'_>>) -> String {
    let mut out = String::new();
    let mut escaped = false;
    for ch in chars.by_ref() {
        if escaped {
            out.push(ch);
            escaped = false;
        } else if ch == '\\' {
            escaped = true;
        } else if ch == '"' {
            break;
        } else {
            out.push(ch);
        }
    }
    out
}

/// Checks one flat item object body. Quoted values and nested objects are consumed whole,
/// so a label or nested metadata object containing `"kind":"hanzi"` or `"score":9` never counts.
fn object_has_completed_hanzi(body: &str) -> bool {
    let mut chars = body.chars().peekable();
    let mut is_hanzi = false;
    let mut score = 0u32;
    while let Some(ch) = chars.next() {
        if ch == '{' {
            let mut nested = 1usize;
            while let Some(c) = chars.next() {
                match c {
                    '"' => {
                        let _ = read_json_string(&mut chars);
                    }
                    '{' => nested += 1,
                    '}' => {
                        nested -= 1;
                        if nested == 0 {
                            break;
                        }
                    }
                    _ => {}
                }
            }
            continue;
        }
        if ch != '"' {
            continue;
        }
        let key = read_json_string(&mut chars);
        while chars.next_if(|c| c.is_whitespace()).is_some() {}
        if chars.next_if_eq(&':').is_none() {
            continue;
        }
        while chars.next_if(|c| c.is_whitespace()).is_some() {}
        if chars.next_if_eq(&'"').is_some() {
            let value = read_json_string(&mut chars);
            if key == "kind" {
                is_hanzi = value == "hanzi";
            }
        } else if chars.next_if_eq(&'{').is_some() {
            let mut nested = 1usize;
            while let Some(c) = chars.next() {
                match c {
                    '"' => {
                        let _ = read_json_string(&mut chars);
                    }
                    '{' => nested += 1,
                    '}' => {
                        nested -= 1;
                        if nested == 0 {
                            break;
                        }
                    }
                    _ => {}
                }
            }
        } else {
            let mut token = String::new();
            while let Some(c) = chars.next_if(|c| *c != ',') {
                token.push(c);
            }
            if key == "score" {
                score = token.trim().parse::<u32>().unwrap_or(0);
            }
        }
    }
    is_hanzi && score > 0
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

        // Nested objects must not break brace depth or leak inner score fields.
        assert!(has_completed_hanzi(
            r#"[{"kind":"hanzi","meta":{"a":1},"score":90}]"#
        ));
        assert!(!has_completed_hanzi(
            r#"[{"kind":"hanzi","meta":{"score":99},"score":0}]"#
        ));

        // Text inside a quoted label is never a field.
        assert!(!has_completed_hanzi(
            r#"[{"kind":"photo","label":"\"kind\":\"hanzi\",\"score\":99","ref":"a.webp","score":0}]"#
        ));
        assert!(!has_completed_hanzi(
            r#"[{"kind":"hanzi","label":"x\"score\":99","ref":"永","score":0}]"#
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
