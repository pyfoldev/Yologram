//! Очистка словарей-кулдаунов в памяти. Порт `bot/ttl.py`.
use std::collections::HashMap;
use std::hash::Hash;

/// Удаляет записи, где время старше `max_age`. Возвращает число удалённых.
pub fn prune_stale<K: Eq + Hash>(d: &mut HashMap<K, f64>, max_age: f64, now: f64) -> usize {
    let before = d.len();
    d.retain(|_, v| now - *v <= max_age);
    before - d.len()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn prune() {
        let mut d = HashMap::from([(1, 0.0), (2, 90.0), (3, 100.0)]);
        assert_eq!(prune_stale(&mut d, 50.0, 100.0), 1);
        assert!(!d.contains_key(&1) && d.contains_key(&2));
    }
}
