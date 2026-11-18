//! Кого предлагать к массовому исключению. Порт `bot/cleanup.py`.
use crate::db::Row;
use std::collections::{HashMap, HashSet};

pub const MAX_KICK: usize = 200;

fn i(m: &Row, k: &str) -> Option<i64> {
    m.get(k).and_then(|v| v.as_i64())
}

/// (когда человек был активен, писал ли он вообще).
pub fn activity(m: &Row, last_msg: Option<&HashMap<i64, i64>>) -> (Option<i64>, bool) {
    let uid = i(m, "user_id").unwrap_or(0);
    if let Some(lm) = last_msg {
        if let Some(t) = lm.get(&uid) {
            return (Some(*t), true);
        }
    }
    (i(m, "joined_at").filter(|x| *x != 0).or(i(m, "first_seen")), false)
}

/// kind: «неактив» | «новичков» | «молчунов».
pub fn pick_candidates(kind: &str, members: &[Row], writers: &HashSet<i64>, now: i64, period: i64, exclude: &HashSet<i64>, last_msg: Option<&HashMap<i64, i64>>) -> Vec<i64> {
    let mut out = vec![];
    for m in members {
        let uid = i(m, "user_id").unwrap_or(0);
        if exclude.contains(&uid) {
            continue;
        }
        let ok = match kind {
            "неактив" => {
                if last_msg.is_some() {
                    activity(m, last_msg).0.is_some_and(|ts| now - ts > period)
                } else {
                    i(m, "last_seen").is_some_and(|t| now - t > period)
                }
            }
            "новичков" => i(m, "joined_at").is_some_and(|t| now - t < period),
            _ => {
                let since = i(m, "joined_at").filter(|x| *x != 0).or(i(m, "first_seen"));
                !writers.contains(&uid) && since.is_some_and(|s| now - s > period)
            }
        };
        if ok {
            out.push(uid);
        }
    }
    out.truncate(MAX_KICK);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    fn m(u: i64, first: i64, last: i64, joined: Option<i64>) -> Row {
        json!({"user_id": u, "first_seen": first, "last_seen": last, "joined_at": joined}).as_object().unwrap().clone()
    }
    #[test]
    fn pick() {
        let ms = vec![m(1, 0, 10, None), m(2, 0, 990, Some(995)), m(3, 0, 500, Some(0))];
        let none = HashSet::new();
        assert_eq!(pick_candidates("неактив", &ms, &none, 1000, 100, &none, None), vec![1, 3]);
        assert_eq!(pick_candidates("новичков", &ms, &none, 1000, 100, &none, None), vec![2]);
        let w: HashSet<i64> = [3].into();
        assert_eq!(pick_candidates("молчунов", &ms, &w, 1000, 100, &[1].into(), None), vec![2].into_iter().filter(|_| false).collect::<Vec<_>>());
    }
}
