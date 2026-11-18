//! Совместимость: Rust открывает БД, созданную и заполненную Python-ботом (tests/fixtures/py.db, см. tools/gen_db_fixture.py),
//! и даёт те же ответы; плюс самостоятельные сценарии на :memory:.
use chat_helper::db::*;

#[test]
fn reads_python_made_db() {
    let tmp = std::env::temp_dir().join("chat_helper_py_compat.db");
    std::fs::copy(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/py.db"), &tmp).unwrap();
    let db = Database::connect(tmp.to_str().unwrap()).unwrap();
    assert_eq!(db.get_balance(1, 10).unwrap(), 1500 - 200);
    assert_eq!(db.get_warns(1, 10, Some(1_000_000)).unwrap(), 2);
    assert_eq!(db.get_rank(1, 10).unwrap(), 3);
    let s = db.get_settings(1).unwrap();
    assert_eq!(s["warn_limit"], Setting::Int(5));
    assert_eq!(s["links_mode"], Setting::Str("all".into()));
    assert_eq!(db.find_user("@Vasya").unwrap(), Some(10));
    assert_eq!(db.count_by_user(1, None, 10).unwrap(), (7, vec![(10, 5), (11, 2)]));
    let g = db.get_garden(1, 10).unwrap().unwrap();
    assert_eq!(g.slots, 3);
    assert_eq!(db.get_captcha("tok").unwrap().unwrap()["answer"], serde_json::json!(["a", "b"]));
}

#[test]
fn economy_and_quotas() {
    let db = Database::connect(":memory:").unwrap();
    assert_eq!(db.get_balance(1, 1).unwrap(), START_BALANCE);
    assert!(db.try_spend(1, 1, 400).unwrap() && !db.try_spend(1, 1, 700).unwrap());
    assert!(db.transfer(1, 1, 2, 100).unwrap() && !db.transfer(1, 1, 1, 5).unwrap());
    assert_eq!((db.get_balance(1, 1).unwrap(), db.get_balance(1, 2).unwrap()), (500, 1100));
    assert_eq!(db.claim_bonus(1, 1, 5 * DAY).unwrap(), Some(BONUS_AMOUNT));
    assert_eq!(db.claim_bonus(1, 1, 5 * DAY + 10).unwrap(), None);
    assert!(db.take_quota(1, "ai", 0, 2).unwrap() && db.take_quota(1, "ai", 0, 2).unwrap() && !db.take_quota(1, "ai", 0, 2).unwrap());
    assert_eq!(db.promo_redeem("X", 1, 0).unwrap(), ("none", 0));
    db.promo_create("X", 14, 1, 0, 0).unwrap();
    assert_eq!(db.promo_redeem("X", 1, 0).unwrap(), ("ok", 14));
    assert_eq!(db.promo_redeem("X", 1, 0).unwrap(), ("used", 0));
    assert_eq!(db.promo_redeem("X", 2, 0).unwrap(), ("none", 0));
}

#[test]
fn warns_filters_stats() {
    let db = Database::connect(":memory:").unwrap();
    assert_eq!(db.add_warn(1, 5, Some(100), Some(50), "r", 9).unwrap(), 1);
    assert_eq!(db.add_warn(1, 5, Some(110), None, "r", 9).unwrap(), 2);
    assert_eq!(db.get_warns(1, 5, Some(200)).unwrap(), 1); // первое истекло
    assert_eq!(db.remove_warn(1, 5, Some(200)).unwrap(), 0);
    assert!(db.add_filter(1, "word", "x", 1, 0, 50).unwrap().is_some() && db.add_filter(1, "word", "x", 1, 0, 50).unwrap().is_none());
    assert_eq!(db.lists_version(1), 1);
    db.record_message(1, 2, 3600 * 5);
    db.record_message(1, 2, 3600 * 5 + 1);
    assert_eq!(db.user_total(1, 2, None).unwrap(), 2);
    db.remember_user(2, Some("@Bob"), "Боб").unwrap();
    assert_eq!(db.search_users("БОБ", 5).unwrap().len(), 1);
    db.set_setting(1, "warn_limit", 7).unwrap();
    assert_eq!(db.get_settings(1).unwrap()["warn_limit"], Setting::Int(7));
    assert!(db.set_setting(1, "nope", 1).is_err());
}
