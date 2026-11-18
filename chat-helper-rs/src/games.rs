//! Игры: камень-ножницы-бумага, слоты, крестики-нолики, огород. Порт `bot/games/*.py`.
use serde::{Deserialize, Serialize};

// ---------- rps ----------
pub const CHOICES: [&str; 3] = ["камень", "ножницы", "бумага"];

fn beats(a: &str) -> Option<&'static str> {
    match a {
        "камень" => Some("ножницы"),
        "ножницы" => Some("бумага"),
        "бумага" => Some("камень"),
        _ => None,
    }
}

/// 1 — побеждает a, -1 — b, 0 — ничья.
pub fn judge(a: &str, b: &str) -> Result<i64, String> {
    if a == b {
        return Ok(0);
    }
    let w = beats(a).ok_or_else(|| format!("KeyError: {a}"))?;
    Ok(if w == b { 1 } else { -1 })
}

// ---------- slots ----------
pub const JACKPOT: i64 = 64;
pub const TRIPLES: [i64; 3] = [1, 22, 43];

/// +выигрыш или -bet при проигрыше.
pub fn slots_payout(dice_value: i64, bet: i64) -> i64 {
    if dice_value == JACKPOT {
        bet * 9
    } else if TRIPLES.contains(&dice_value) {
        bet * 4
    } else {
        -bet
    }
}

// ---------- tictactoe ----------
pub const LINES: [(usize, usize, usize); 8] =
    [(0, 1, 2), (3, 4, 5), (6, 7, 8), (0, 3, 6), (1, 4, 7), (2, 5, 8), (0, 4, 8), (2, 4, 6)];

pub fn new_board() -> Vec<String> {
    vec![" ".to_string(); 9]
}

/// idx вне 0..9 или занятая клетка — Ok(false). Доска короче 9 клеток при idx внутри 0..9 — IndexError, как в Python.
pub fn r#move(board: &mut [String], idx: i64, mark: &str) -> Result<bool, String> {
    if !(0..9).contains(&idx) {
        return Ok(false);
    }
    let cell = board.get_mut(idx as usize).ok_or("IndexError: list index out of range")?;
    if *cell != " " {
        return Ok(false);
    }
    *cell = mark.to_string();
    Ok(true)
}

/// Some(mark) | Some("draw") | None.
pub fn winner(board: &[String]) -> Result<Option<String>, String> {
    let at = |i: usize| board.get(i).ok_or_else(|| "IndexError: list index out of range".to_string());
    for (a, b, c) in LINES {
        let (x, y, z) = (at(a)?, at(b)?, at(c)?);
        if x != " " && x == y && y == z {
            return Ok(Some(x.clone()));
        }
    }
    Ok(if board.iter().any(|c| c == " ") { None } else { Some("draw".into()) })
}

// ---------- garden ----------
pub const H: i64 = 3600;
pub const START_SLOTS: i64 = 3;
pub const MAX_SLOTS: i64 = 9;
pub const WATER_COOLDOWN: i64 = H;
pub const EXPAND_STEP: i64 = 300;

#[derive(Debug, Clone, Copy)]
pub struct Crop {
    pub name: &'static str,
    pub emoji: &'static str,
    pub cost: i64,
    pub seconds: i64,
    pub reward: i64,
}

pub const CROPS: [Crop; 4] = [
    Crop { name: "морковь", emoji: "🥕", cost: 20, seconds: 2 * H, reward: 45 },
    Crop { name: "клубника", emoji: "🍓", cost: 80, seconds: 4 * H, reward: 200 },
    Crop { name: "картошка", emoji: "🥔", cost: 50, seconds: 6 * H, reward: 130 },
    Crop { name: "тыква", emoji: "🎃", cost: 150, seconds: 24 * H, reward: 420 },
];

pub fn crop(name: &str) -> Option<&'static Crop> {
    CROPS.iter().find(|c| c.name == name)
}

const STEMS: [(&str, &str); 4] =
    [("морков", "морковь"), ("клубник", "клубника"), ("картош", "картошка"), ("тыкв", "тыква")];

/// Python `str.strip().lower()` + startswith по корням.
pub fn find_crop(word: &str) -> Option<&'static str> {
    let w = word.trim().to_lowercase();
    STEMS.iter().find(|(stem, _)| w.starts_with(stem)).map(|(_, n)| *n)
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Plot {
    pub crop: String,
    pub planted: i64,
    pub ready: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Garden {
    pub slots: i64,
    pub plots: Vec<Option<Plot>>,
    pub last_water: Option<i64>,
}

impl Garden {
    pub fn new() -> Self {
        Garden { slots: START_SLOTS, plots: vec![None; START_SLOTS as usize], last_water: None }
    }

    /// Номер грядки или None, если места нет.
    pub fn plant(&mut self, crop_name: &str, now: i64) -> Result<Option<usize>, String> {
        let c = crop(crop_name).ok_or_else(|| format!("KeyError: {crop_name}"))?;
        for (i, p) in self.plots.iter_mut().enumerate() {
            if p.is_none() {
                *p = Some(Plot { crop: crop_name.into(), planted: now, ready: now + c.seconds });
                return Ok(Some(i));
            }
        }
        Ok(None)
    }

    pub fn harvest(&mut self, now: i64) -> Result<Vec<(String, i64)>, String> {
        let mut out = vec![];
        for p in self.plots.iter_mut() {
            if let Some(pl) = p {
                if pl.ready <= now {
                    let c = crop(&pl.crop).ok_or_else(|| format!("KeyError: {}", pl.crop))?;
                    out.push((pl.crop.clone(), c.reward));
                    *p = None;
                }
            }
        }
        Ok(out)
    }

    /// (сколько грядок полито, секунд до следующего полива).
    pub fn water(&mut self, now: i64) -> (i64, i64) {
        let growing = self.plots.iter().flatten().filter(|p| p.ready > now).count() as i64;
        if growing == 0 {
            return (0, 0);
        }
        if let Some(lw) = self.last_water {
            let wait = lw + WATER_COOLDOWN - now;
            if wait > 0 {
                return (0, wait);
            }
        }
        for p in self.plots.iter_mut().flatten().filter(|p| p.ready > now) {
            // int(x * 0.9), усечение к нулю, как в Python (значения положительные)
            p.ready = now + ((p.ready - now) as f64 * (1.0 - 0.10)) as i64;
        }
        self.last_water = Some(now);
        (growing, 0)
    }

    pub fn expand_cost(&self) -> Option<i64> {
        if self.slots >= MAX_SLOTS { None } else { Some(EXPAND_STEP * (self.slots - START_SLOTS + 1)) }
    }

    pub fn expand(&mut self) -> bool {
        if self.slots >= MAX_SLOTS {
            return false;
        }
        self.slots += 1;
        self.plots.push(None);
        true
    }

    pub fn render(&self, now: i64) -> Result<String, String> {
        let mut lines = vec![];
        for (i, p) in self.plots.iter().enumerate() {
            let n = i + 1;
            match p {
                None => lines.push(format!("{n}. ▫️ пусто")),
                Some(p) => {
                    let c = crop(&p.crop).ok_or_else(|| format!("KeyError: {}", p.crop))?;
                    if p.ready <= now {
                        lines.push(format!("{n}. {} {} — готово ✅", c.emoji, c.name));
                    } else {
                        lines.push(format!("{n}. 🌱 {} — осталось {}", c.name, fmt_left(p.ready - now)));
                    }
                }
            }
        }
        Ok(lines.join("\n"))
    }
}

impl Default for Garden {
    fn default() -> Self {
        Self::new()
    }
}

pub fn fmt_left(seconds: i64) -> String {
    let seconds = seconds.max(0);
    let mins = seconds / 60;
    let (h, m) = (mins / 60, mins % 60);
    if h > 0 { format!("{h} ч {m} мин") } else { format!("{} мин", m.max(1)) }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rps() {
        assert_eq!(judge("камень", "ножницы"), Ok(1));
        assert_eq!(judge("камень", "бумага"), Ok(-1));
        assert_eq!(judge("бумага", "бумага"), Ok(0));
    }

    #[test]
    fn slots() {
        assert_eq!(slots_payout(64, 10), 90);
        assert_eq!(slots_payout(22, 10), 40);
        assert_eq!(slots_payout(2, 10), -10);
    }

    #[test]
    fn ttt() {
        let mut b = new_board();
        assert_eq!(r#move(&mut b, 0, "X"), Ok(true));
        assert_eq!(r#move(&mut b, 0, "O"), Ok(false));
        assert_eq!(r#move(&mut b, 9, "O"), Ok(false));
        assert_eq!(r#move(&mut b, -1, "O"), Ok(false));
        r#move(&mut b, 4, "X").unwrap();
        r#move(&mut b, 8, "X").unwrap();
        assert_eq!(winner(&b).unwrap().as_deref(), Some("X"));
        assert!(r#move(&mut vec![], 2, "X").is_err());
    }

    #[test]
    fn garden_flow() {
        let mut g = Garden::new();
        assert_eq!(g.plant("морковь", 0).unwrap(), Some(0));
        assert_eq!(g.water(0), (1, 0));
        assert_eq!(g.plots[0].as_ref().unwrap().ready, 6480);
        assert_eq!(g.water(10), (0, 3590));
        assert_eq!(g.harvest(6480).unwrap(), vec![("морковь".to_string(), 45)]);
        assert_eq!(find_crop(" Тыква "), Some("тыква"));
        assert_eq!(fmt_left(0), "1 мин");
        assert_eq!(fmt_left(3 * 3600 + 120), "3 ч 2 мин");
        assert_eq!(g.expand_cost(), Some(300));
    }
}
