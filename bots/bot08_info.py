"""БОТ №8 — ИНФОРМЕР: погода и курсы валют (работа с внешним API).

Чему учит:
  • асинхронные HTTP-запросы через httpx (не блокируют бота);
  • обработка ошибок сети;
  • API без ключей: Open-Meteo (погода) и open.er-api.com (курсы).

Запуск:  python -m bots.bot08_info
Использование:  /weather Москва     /rate USD     /rate 100 EUR RUB
Как расширить: добавьте новую async-функцию-«источник» и команду к ней.
"""
import httpx
from aiogram import Router
from aiogram.filters import Command, CommandObject, CommandStart
from aiogram.types import Message

from .core import run_bot

router = Router()
TIMEOUT = httpx.Timeout(10.0)  # не ждём внешний сервис вечно

# Расшифровка кодов погоды Open-Meteo (WMO) — сокращённая.
WEATHER_CODES = {
    0: "☀️ ясно", 1: "🌤 малооблачно", 2: "⛅️ облачно", 3: "☁️ пасмурно",
    45: "🌫 туман", 51: "🌦 морось", 61: "🌧 дождь", 71: "🌨 снег", 80: "🌧 ливень", 95: "⛈ гроза",
}


class ApiError(Exception):
    """Понятная пользователю ошибка (город не найден, сервис недоступен)."""


async def fetch_json(url: str, **params) -> dict:
    try:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            r = await client.get(url, params=params)
            r.raise_for_status()
            return r.json()
    except httpx.HTTPError as e:
        raise ApiError("Сервис временно недоступен, попробуйте позже.") from e


async def get_weather(city: str) -> str:
    # Шаг 1: название города -> координаты.
    geo = await fetch_json(
        "https://geocoding-api.open-meteo.com/v1/search", name=city, count=1, language="ru"
    )
    if not geo.get("results"):
        raise ApiError(f"Город «{city}» не найден.")
    place = geo["results"][0]
    # Шаг 2: координаты -> текущая погода.
    data = await fetch_json(
        "https://api.open-meteo.com/v1/forecast",
        latitude=place["latitude"], longitude=place["longitude"],
        current="temperature_2m,weather_code,wind_speed_10m",
    )
    cur = data["current"]
    desc = WEATHER_CODES.get(cur["weather_code"], "—")
    return (
        f"<b>{place['name']}</b>, {place.get('country', '')}\n"
        f"{desc}, {cur['temperature_2m']}°C\n💨 ветер {cur['wind_speed_10m']} км/ч"
    )


async def get_rate(amount: float, base: str, target: str | None) -> str:
    data = await fetch_json(f"https://open.er-api.com/v6/latest/{base.upper()}")
    if data.get("result") != "success":
        raise ApiError(f"Валюта «{base}» не найдена.")
    rates = data["rates"]
    targets = [target.upper()] if target else ["USD", "EUR", "RUB"]
    lines = []
    for t in targets:
        if t not in rates:
            raise ApiError(f"Валюта «{t}» не найдена.")
        if t != base.upper():
            lines.append(f"{amount:g} {base.upper()} = <b>{amount * rates[t]:,.2f}</b> {t}")
    return "\n".join(lines) or "Укажите разные валюты."


@router.message(CommandStart())
async def start(message: Message) -> None:
    await message.answer(
        "🌍 <b>Информер</b>\n\n/weather <i>город</i>\n/rate <i>USD</i>\n/rate <i>100 EUR RUB</i>"
    )


@router.message(Command("weather"))
async def cmd_weather(message: Message, command: CommandObject) -> None:
    if not command.args:
        await message.answer("Укажите город: /weather Москва")
        return
    try:
        await message.answer(await get_weather(command.args.strip()))
    except ApiError as e:
        await message.answer(f"⚠️ {e}")


@router.message(Command("rate"))
async def cmd_rate(message: Message, command: CommandObject) -> None:
    args = (command.args or "USD").split()
    try:
        # Форматы: "USD" | "100 EUR" | "100 EUR RUB"
        if args[0].replace(".", "", 1).isdigit():
            amount, rest = float(args[0]), args[1:]
        else:
            amount, rest = 1.0, args
        if not rest:
            raise ApiError("Пример: /rate 100 EUR RUB")
        text = await get_rate(amount, rest[0], rest[1] if len(rest) > 1 else None)
        await message.answer(text)
    except ApiError as e:
        await message.answer(f"⚠️ {e}")


if __name__ == "__main__":
    run_bot(router)
