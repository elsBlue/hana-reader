# Hana

A calm Android ebook reader that reads to you in a warm female voice. Built for books you would rather hear than stare at — English neural voices, with Indonesian books readable via System TTS.

## What it does

- Minimal library + reader
- **Listen like music**: lock the phone, Hana keeps reading from a notification
- **Offline neural voices** after one download (English only for now)
  - English **Smooth** (default Listen): **Piper** `en_US-lessac-medium` — slower, with pauses
  - English **Warm**: **Piper** `en_US-amy-medium` — softer, commas kept, lower buzz
- Falls back to the device TTS voice if the neural pack is missing (including Indonesian books)
- Google sign-in + local progress saving
- **Add your book** first after login (EPUB / TXT / Markdown); built-in library is secondary
- English and Indonesian sample library to start

## Download the APK

GitHub Actions builds a debug APK on every push to `main`.

1. Open the **Actions** tab
2. Open the latest **Build APKs** run
3. Download **hana-reader-debug-apk**
4. Unzip and install `app-debug.apk` (enable Install unknown apps)

You can also run the workflow by hand: **Actions → Build APKs → Run workflow**.

## Google sign-in

Hana uses the same Google Cloud **web client ID** as Buck Manager. For Google login to work on this package (`com.hana.reader`) add an **Android OAuth client** in Google Cloud Console with:

- Package name: `com.hana.reader`
- SHA-1 of the debug keystore used by Actions (print it after the first successful run)

Until that is added, **Continue locally** still works and progress is saved on the phone.

## Voice

Tap **Listen** when you are ready — the first English voice download is intentional, with a clear **%** progress (Smooth ~67 MB). **Warm** (~64 MB) is optional in Voices / MiniPlayer. Indonesian books use the device System TTS for now. After the English pack is installed, listening works in airplane mode.

Listen follows the Piper mobile guide: Smooth is the default (length 1.15), Warm is Amy at 1.15 with lower noise, and every sentence gets a 400ms breath. English chunks prefer whole sentences under ~300 characters so Warm does not jump mid-sentence. The reader auto-scrolls to the active sentence without claiming word-level highlight sync.

Tap the caption on the mini player to switch **Hana · neural** (Piper) and **Device** (system TTS).

**Voice settings** (Voices screen → tune icon, or MiniPlayer → tune): Speed, Pace, Texture, and Breath. Lower Texture to reduce Warm buzz. Pitch is not available for offline Hana voices. Settings persist per voice (Smooth / Warm).

The web preview uses the browser voice. This Android app uses sherpa-onnx so playback still works with the screen locked.

See `NOTICE` for licenses and download URLs.

## Signed release

Set repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, then run the workflow with **Build signed release APK**.
