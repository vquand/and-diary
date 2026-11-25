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

fn entry_date(filename: &str) -> Option<NaiveDate> {
    let date = filename.strip_suffix(".ink")?;
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
        assert!(entry_date("2024-02-29.ink.new").is_none());
        assert!(entry_date("2025-02-29.ink").is_none());
        assert!(entry_date("2024-2-9.ink").is_none());
        assert!(saved_days(Path::new("/dev/null")).is_empty());
    }
}
