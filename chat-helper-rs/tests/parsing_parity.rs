//! Сравнение с записью поведения Python-оригинала (tests/fixtures/parsing.json).
use chat_helper::parsing::*;
use serde_json::Value;

fn sp(v: &Value) -> Option<Span> {
    v.as_i64().map(Span)
}

#[test]
fn parity_with_python() {
    let cases: Vec<Value> = serde_json::from_str(include_str!("fixtures/parsing.json")).unwrap();
    let mut bad = vec![];
    for c in &cases {
        let t = c["text"].as_str().unwrap();
        let mut errs = vec![];
        if parse_duration(t) != sp(&c["duration"]) {
            errs.push(format!("duration {:?} vs {}", parse_duration(t), c["duration"]));
        }
        if parse_period(t) != sp(&c["period"]) {
            errs.push(format!("period {:?} vs {}", parse_period(t), c["period"]));
        }
        let got = parse_mod_command(t).map(|m| {
            serde_json::json!({"name": m.name, "target": m.target, "duration": m.duration.map(|s| s.0), "reason": m.reason})
        });
        if got.clone().unwrap_or(Value::Null) != c["mod"] {
            errs.push(format!("mod {got:?} vs {}", c["mod"]));
        }
        let st = parse_stats_command(t).map(|s| match s {
            StatsPeriod::Hours(h) => serde_json::json!(["hours", h]),
            StatsPeriod::All => serde_json::json!(["all", null]),
        });
        if st.clone().unwrap_or(Value::Null) != c["stats"] {
            errs.push(format!("stats {st:?} vs {}", c["stats"]));
        }
        if !errs.is_empty() {
            bad.push(format!("{t:?}: {}", errs.join("; ")));
        }
    }
    assert!(bad.is_empty(), "{} из {} расхождений:\n{}", bad.len(), cases.len(), bad.join("\n"));
}
