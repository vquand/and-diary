use chrono::NaiveDate;
use std::{
    collections::BTreeMap,
    fs::{self, File},
    io::{self, Write},
    path::Path,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[repr(u8)]
pub enum Pin {
    Star = 1,
    Heart = 2,
    Flower = 3,
    Sun = 4,
    Important = 5,
    Cat = 6,
    Dog = 7,
    Slime = 8,
    Dragon = 9,
    Sunrise = 10,
    Rain = 11,
    Mist = 12,
    Cloud = 13,
}

impl Pin {
    pub const ALL: [Self; 13] = [
        Self::Star,
        Self::Heart,
        Self::Flower,
        Self::Sun,
        Self::Important,
        Self::Cat,
        Self::Dog,
        Self::Slime,
        Self::Dragon,
        Self::Sunrise,
        Self::Rain,
        Self::Mist,
        Self::Cloud,
    ];

    #[cfg(target_os = "android")]
    pub fn label(self) -> &'static str {
        match self {
            Self::Star => "Star",
            Self::Heart => "Heart",
            Self::Flower => "Flower",
            Self::Sun => "Sun",
            Self::Important => "Important",
            Self::Cat => "Cat",
            Self::Dog => "Dog",
            Self::Slime => "Slime",
            Self::Dragon => "Dragon",
            Self::Sunrise => "Sunrise",
            Self::Rain => "Rain",
            Self::Mist => "Mist",
            Self::Cloud => "Cloud",
        }
    }
}

pub fn load(directory: &Path) -> BTreeMap<NaiveDate, Pin> {
    fs::read_dir(directory)
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .filter_map(|entry| {
            let path = entry.path();
            if path.extension()?.to_str()? != "pin" || entry.metadata().ok()?.len() != 1 {
                return None;
            }
            let date = NaiveDate::parse_from_str(path.file_stem()?.to_str()?, "%Y-%m-%d").ok()?;
            let data = fs::read(path).ok()?;
            let pin = Pin::ALL.into_iter().find(|pin| data == [*pin as u8])?;
            Some((date, pin))
        })
        .collect()
}

pub fn save(directory: &Path, date: NaiveDate, pin: Option<Pin>) -> io::Result<()> {
    let path = directory.join(format!("{date}.pin"));
    let Some(pin) = pin else {
        return match fs::remove_file(path) {
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            result => result,
        };
    };

    fs::create_dir_all(directory)?;
    let staged = path.with_extension("pin.tmp");
    let result = (|| {
        let mut file = File::create(&staged)?;
        file.write_all(&[pin as u8])?;
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
    fn replaces_and_removes_only_the_requested_date() {
        let directory = std::env::temp_dir().join(format!("and-diary-pins-{}", std::process::id()));
        let first = NaiveDate::from_ymd_opt(2026, 11, 25).unwrap();
        let second = first.succ_opt().unwrap();

        save(&directory, first, Some(Pin::Star)).unwrap();
        save(&directory, second, Some(Pin::Sun)).unwrap();
        save(&directory, first, Some(Pin::Heart)).unwrap();
        assert_eq!(
            load(&directory),
            BTreeMap::from([(first, Pin::Heart), (second, Pin::Sun)])
        );

        save(&directory, first, None).unwrap();
        save(&directory, first, None).unwrap();
        assert_eq!(load(&directory), BTreeMap::from([(second, Pin::Sun)]));
        for pin in Pin::ALL {
            save(&directory, first, Some(pin)).unwrap();
            assert_eq!(
                fs::read(directory.join(format!("{first}.pin"))).unwrap(),
                [pin as u8]
            );
            assert_eq!(load(&directory).get(&first), Some(&pin));
            assert_eq!(load(&directory).get(&second), Some(&Pin::Sun));
        }
        fs::remove_dir_all(directory).unwrap();
    }
}
