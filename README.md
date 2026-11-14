<p align="center">
  <img src="docs/banner.svg" alt="Nullgram" width="100%">
</p>

## Возможности

- Material You: цвет темы, плотность, скругление, AMOLED
- Режим призрака: без отметок о прочтении, набора текста и статуса «в сети»
- Сохранение удалённых сообщений, истории правок и исчезающих фото
- Мысли, баннер профиля, обои главного экрана
- WebSocket-прокси, очистка ссылок, экспорт чата
- До 16 аккаунтов, без рекламы, быстрые загрузки

Настройки: **Настройки → Nullgram**.

## Сборка

JDK 21, Android SDK 36, NDK 27.2.12479018. Ключи с [my.telegram.org](https://my.telegram.org) в `local.properties`:

```properties
NULLGRAM_API_ID=123456
NULLGRAM_API_HASH=ваш_hash
NULLGRAM_SYNC_URL=http://ваш-сервер:8787  # необязательно, сервер: server/
```

```bash
./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
```

## Лицензия

GPL v2. Основан на [Telegram для Android](https://github.com/DrKLO/Telegram), неофициальный клиент.
