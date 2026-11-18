//! Python bindings for `bot/games/`: generated from the signatures in `port.rs` by rust-anything.
//! Regenerate with `rust-anything refresh` after changing them.

use pyo3::prelude::*;

pub mod js;
pub mod port;
pub mod pyconv;
pub mod pynum;

#[allow(unused_imports)]
use pynum::Num;

fn error(message: String) -> PyErr {
    use pyo3::exceptions as e;
    let (kind, text) = message.split_once(": ").unwrap_or(("", message.as_str()));
    let text = text.to_string();
    match kind {
        "TypeError" => e::PyTypeError::new_err(text),
        "KeyError" => e::PyKeyError::new_err(text),
        "IndexError" => e::PyIndexError::new_err(text),
        "OverflowError" => e::PyOverflowError::new_err(text),
        "ZeroDivisionError" => e::PyZeroDivisionError::new_err(text),
        "AttributeError" => e::PyAttributeError::new_err(text),
        "RuntimeError" => e::PyRuntimeError::new_err(text),
        "NotImplementedError" => e::PyNotImplementedError::new_err(text),
        "LookupError" => e::PyLookupError::new_err(text),
        "ArithmeticError" => e::PyArithmeticError::new_err(text),
        "UnicodeError" => e::PyUnicodeError::new_err(text),
        "ValueError" => e::PyValueError::new_err(text),
        _ => e::PyValueError::new_err(message),
    }
}

fn find_crop_inner(word: String) -> Result<Option<String>, String> {
    let out = port::find_crop(word)?;
    Ok(out)
}

/// `find_crop`.
#[pyfunction]
#[pyo3(name = "find_crop", signature = (word))]
fn find_crop(word: String) -> PyResult<Option<String>> {
    find_crop_inner(word).map_err(error)
}

fn new_garden_inner() -> Result<serde_json::Value, String> {
    let out = port::new_garden()?;
    Ok(out)
}

/// `new_garden`.
#[pyfunction]
#[pyo3(name = "new_garden", signature = ())]
fn new_garden(py: Python<'_>) -> PyResult<Py<PyAny>> {
    new_garden_inner()
        .map_err(error)
        .and_then(|v| crate::pyconv::ToPy::to_py(&v, py))
}

fn plant_inner(g: Bound<'_, PyAny>, crop: String, now: i64) -> Result<Option<i64>, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::plant(g, crop, now)?;
    Ok(out)
}

/// `plant`.
#[pyfunction]
#[pyo3(name = "plant", signature = (g, crop, now))]
fn plant(g: Bound<'_, PyAny>, crop: String, now: i64) -> PyResult<Option<i64>> {
    plant_inner(g, crop, now).map_err(error)
}

fn harvest_inner(g: Bound<'_, PyAny>, now: i64) -> Result<Vec<serde_json::Value>, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::harvest(g, now)?;
    Ok(out)
}

/// `harvest`.
#[pyfunction]
#[pyo3(name = "harvest", signature = (g, now))]
fn harvest(py: Python<'_>, g: Bound<'_, PyAny>, now: i64) -> PyResult<Py<PyAny>> {
    harvest_inner(g, now)
        .map_err(error)
        .and_then(|v| crate::pyconv::ToPy::to_py(&v, py))
}

fn water_inner(g: Bound<'_, PyAny>, now: i64) -> Result<serde_json::Value, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::water(g, now)?;
    Ok(out)
}

/// `water`.
#[pyfunction]
#[pyo3(name = "water", signature = (g, now))]
fn water(py: Python<'_>, g: Bound<'_, PyAny>, now: i64) -> PyResult<Py<PyAny>> {
    water_inner(g, now)
        .map_err(error)
        .and_then(|v| crate::pyconv::ToPy::to_py(&v, py))
}

fn expand_cost_inner(g: Bound<'_, PyAny>) -> Result<Option<i64>, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::expand_cost(g)?;
    Ok(out)
}

/// `expand_cost`.
#[pyfunction]
#[pyo3(name = "expand_cost", signature = (g))]
fn expand_cost(g: Bound<'_, PyAny>) -> PyResult<Option<i64>> {
    expand_cost_inner(g).map_err(error)
}

fn expand_inner(g: Bound<'_, PyAny>) -> Result<bool, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::expand(g)?;
    Ok(out)
}

/// `expand`.
#[pyfunction]
#[pyo3(name = "expand", signature = (g))]
fn expand(g: Bound<'_, PyAny>) -> PyResult<bool> {
    expand_inner(g).map_err(error)
}

fn fmt_left_inner(seconds: i64) -> Result<String, String> {
    let out = port::fmt_left(seconds)?;
    Ok(out)
}

/// `fmt_left`.
#[pyfunction]
#[pyo3(name = "fmt_left", signature = (seconds))]
fn fmt_left(seconds: i64) -> PyResult<String> {
    fmt_left_inner(seconds).map_err(error)
}

fn render_inner(g: Bound<'_, PyAny>, now: i64) -> Result<String, String> {
    let g: serde_json::Value = crate::pyconv::FromPy::from_py(&g)?;
    let out = port::render(g, now)?;
    Ok(out)
}

/// `render`.
#[pyfunction]
#[pyo3(name = "render", signature = (g, now))]
fn render(g: Bound<'_, PyAny>, now: i64) -> PyResult<String> {
    render_inner(g, now).map_err(error)
}

fn judge_inner(a: String, b: String) -> Result<i64, String> {
    let out = port::judge(a, b)?;
    Ok(out)
}

/// `judge`.
#[pyfunction]
#[pyo3(name = "judge", signature = (a, b))]
fn judge(a: String, b: String) -> PyResult<i64> {
    judge_inner(a, b).map_err(error)
}

fn slots_payout_inner(dice_value: i64, bet: i64) -> Result<i64, String> {
    let out = port::slots_payout(dice_value, bet)?;
    Ok(out)
}

/// `slots_payout`.
#[pyfunction]
#[pyo3(name = "slots_payout", signature = (dice_value, bet))]
fn slots_payout(dice_value: i64, bet: i64) -> PyResult<i64> {
    slots_payout_inner(dice_value, bet).map_err(error)
}

fn new_board_inner() -> Result<Vec<String>, String> {
    let out = port::new_board()?;
    Ok(out)
}

/// `new_board`.
#[pyfunction]
#[pyo3(name = "new_board", signature = ())]
fn new_board() -> PyResult<Vec<String>> {
    new_board_inner().map_err(error)
}

fn move_inner(board: Vec<String>, idx: i64, mark: String) -> Result<bool, String> {
    let out = port::r#move(board, idx, mark)?;
    Ok(out)
}

/// `move`.
#[pyfunction]
#[pyo3(name = "move", signature = (board, idx, mark))]
fn r#move(board: Vec<String>, idx: i64, mark: String) -> PyResult<bool> {
    move_inner(board, idx, mark).map_err(error)
}

fn winner_inner(board: Vec<String>) -> Result<Option<String>, String> {
    let out = port::winner(board)?;
    Ok(out)
}

/// `winner`.
#[pyfunction]
#[pyo3(name = "winner", signature = (board))]
fn winner(board: Vec<String>) -> PyResult<Option<String>> {
    winner_inner(board).map_err(error)
}

#[pymodule]
#[pyo3(name = "_native")]
fn native_module(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(find_crop, m)?)?;
    m.add_function(wrap_pyfunction!(new_garden, m)?)?;
    m.add_function(wrap_pyfunction!(plant, m)?)?;
    m.add_function(wrap_pyfunction!(harvest, m)?)?;
    m.add_function(wrap_pyfunction!(water, m)?)?;
    m.add_function(wrap_pyfunction!(expand_cost, m)?)?;
    m.add_function(wrap_pyfunction!(expand, m)?)?;
    m.add_function(wrap_pyfunction!(fmt_left, m)?)?;
    m.add_function(wrap_pyfunction!(render, m)?)?;
    m.add_function(wrap_pyfunction!(judge, m)?)?;
    m.add_function(wrap_pyfunction!(slots_payout, m)?)?;
    m.add_function(wrap_pyfunction!(new_board, m)?)?;
    m.add_function(wrap_pyfunction!(r#move, m)?)?;
    m.add_function(wrap_pyfunction!(winner, m)?)?;
    Ok(())
}
