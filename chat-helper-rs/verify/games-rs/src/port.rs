//! Rust port of `bot/games/`: adapters over the real crate module (`chat-helper-rs/src/games.rs`).
#![forbid(unsafe_code)]

#[path = "../../../src/games.rs"]
mod games;
use games::Garden;
use serde_json::{json, Value};

fn g(v: Value) -> Result<Garden, String> {
    serde_json::from_value(v).map_err(|e| format!("TypeError: {e}"))
}

pub fn find_crop(word: String) -> Result<Option<String>, String> {
    Ok(games::find_crop(&word).map(String::from))
}
pub fn new_garden() -> Result<Value, String> {
    Ok(serde_json::to_value(Garden::new()).unwrap())
}
pub fn plant(v: Value, crop: String, now: i64) -> Result<Option<i64>, String> {
    Ok(g(v)?.plant(&crop, now)?.map(|i| i as i64))
}
pub fn harvest(v: Value, now: i64) -> Result<Vec<Value>, String> {
    Ok(g(v)?.harvest(now)?.into_iter().map(|(c, r)| json!([c, r])).collect())
}
pub fn water(v: Value, now: i64) -> Result<Value, String> {
    let (a, b) = g(v)?.water(now);
    Ok(json!([a, b]))
}
pub fn expand_cost(v: Value) -> Result<Option<i64>, String> {
    Ok(g(v)?.expand_cost())
}
pub fn expand(v: Value) -> Result<bool, String> {
    Ok(g(v)?.expand())
}
pub fn fmt_left(seconds: i64) -> Result<String, String> {
    Ok(games::fmt_left(seconds))
}
pub fn render(v: Value, now: i64) -> Result<String, String> {
    g(v)?.render(now)
}
pub fn judge(a: String, b: String) -> Result<i64, String> {
    games::judge(&a, &b)
}
pub fn slots_payout(dice_value: i64, bet: i64) -> Result<i64, String> {
    Ok(games::slots_payout(dice_value, bet))
}
pub fn new_board() -> Result<Vec<String>, String> {
    Ok(games::new_board())
}
pub fn r#move(mut board: Vec<String>, idx: i64, mark: String) -> Result<bool, String> {
    games::r#move(&mut board, idx, &mark)
}
pub fn winner(board: Vec<String>) -> Result<Option<String>, String> {
    games::winner(&board)
}
