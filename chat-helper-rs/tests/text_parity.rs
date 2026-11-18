//! Сверка filters/links с записью Python-оригинала (tests/fixtures/text.json).
use chat_helper::{filters::*, links::*};
use serde_json::{json, Value};
use std::collections::HashSet;

fn fx() -> Value {
    serde_json::from_str(include_str!("fixtures/text.json")).unwrap()
}

#[test]
fn normalize_parity() {
    for c in fx()["norm"].as_array().unwrap() {
        assert_eq!(normalize(c[0].as_str().unwrap()), c[1].as_str().unwrap(), "normalize({:?})", c[0]);
    }
}

#[test]
fn regex_validation_parity() {
    for c in fx()["regex"].as_array().unwrap() {
        assert_eq!(validate_regex(c[0].as_str().unwrap()).is_ok(), c[1].as_bool().unwrap(), "validate_regex({:?})", c[0]);
    }
}

#[test]
fn filterset_parity() {
    let rows: Vec<_> = [(3, "word", "Спам"), (5, "regex", r"купи\s+\d+"), (4, "word", "xyz"), (9, "regex", "(abc)")]
        .iter()
        .map(|(i, k, p)| json!({"id": i, "kind": k, "pattern": p}).as_object().unwrap().clone())
        .collect();
    let fs = FilterSet::new(&rows);
    for c in fx()["set"].as_array().unwrap() {
        assert_eq!(fs.first_match(c[0].as_str().unwrap()), c[1].as_i64(), "first_match({:?})", c[0]);
    }
}

#[test]
fn links_parity() {
    let f = fx();
    for c in f["domain"].as_array().unwrap() {
        assert_eq!(normalize_domain(c[0].as_str().unwrap()).as_deref(), c[1].as_str(), "normalize_domain({:?})", c[0]);
    }
    for c in f["hosts"].as_array().unwrap() {
        let mut got: Vec<String> = extract_hosts(c[0].as_str().unwrap(), &[]).into_iter().collect();
        got.sort();
        let want: Vec<String> = c[1].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
        assert_eq!(got, want, "extract_hosts({:?})", c[0]);
    }
    for c in f["allowed"].as_array().unwrap() {
        let allow: HashSet<String> = c[0].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
        assert_eq!(host_allowed(c[1].as_str().unwrap(), &allow), c[2].as_bool().unwrap(), "{:?} {:?}", c[0], c[1]);
    }
}
