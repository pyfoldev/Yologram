# Nullgram

Nullgram is an Android Telegram client based on the official [Telegram for Android](https://github.com/DrKLO/Telegram) 13.2.2 source, redesigned in Material You and extended with privacy and quality-of-life features. It uses the standard Telegram API (MTProto) and the same native code as the original.

Licensed under GPL v2, like the original. See [LICENSE](LICENSE).

## Features

- **Material You** interface with one seed colour, adjustable density and corner roundness, AMOLED mode, minimalist icons.
- **Ghost mode**: does not send read receipts, typing status, story views or online status.
- **Saving deleted messages** and **edit history**.
- **Saving disappearing photos and videos** to the gallery.
- **Built-in WebSocket proxy** (local SOCKS5 to Telegram's web endpoints).
- **Link cleaner**, message details, snippets, chat export.
- **Thoughts**: a short text next to your avatar that lives for 24 hours.
- **Profile banner** and **main screen wallpaper**.
- Up to 16 accounts, no ads, faster downloads, New Year and Halloween themes.
- Privacy options: hide stories bar, hide own phone number, confirm calls, block screenshots, disable swipe-to-reply.

## Building

Requirements: JDK 21, Android SDK (compileSdk 36), NDK 27.2.12479018.

1. Get your own Telegram API credentials at <https://my.telegram.org> and add them to `local.properties`:

   ```properties
   NULLGRAM_API_ID=123456
   NULLGRAM_API_HASH=your_api_hash
   ```

   Without them the client builds with the public test credentials from the upstream source, which Telegram rate-limits.

2. Optionally set the address of a profile-sync server (see below):

   ```properties
   NULLGRAM_SYNC_URL=http://your-server:8787
   ```

   Leave it unset to keep the banner and thoughts on the device only.

3. Build an arm64 APK:

   ```bash
   ./gradlew :TMessagesProj_App:assembleAfatDebug -Pandroid.injected.build.abi=arm64-v8a -Pandroid.injected.testOnly=false
   ```

   The APK is written to `TMessagesProj_App/build/intermediates/apk/afat/debug/app.apk`.

   The default `.beta` package id lets it install next to the official app. Sign it with your own key if you distribute builds.

## Profile sync server

`server/nullgram_server.py` is a small standard-library Python service that stores each user's banner and thought so other Nullgram users can see them.

```bash
python3 server/nullgram_server.py   # listens on 0.0.0.0:8787
```

The first client to write for a Telegram user id receives a secret token and is the only one allowed to change that entry afterwards. Traffic is plain HTTP; put it behind HTTPS if you care about token privacy.

## Credits

Telegram for Android by Telegram FZ-LLC and contributors. Nullgram is an unofficial client and is not affiliated with Telegram.
