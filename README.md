# Hana

A calm Android ebook reader that reads to you in a warm female voice. Built for books you would rather hear than stare at — English and Indonesian.

## What it does

- Minimal library + reader
- **Listen like music**: lock the phone, Hana keeps reading from a notification
- **Offline neural voices** after one download per language
  - English **Hana**: **Kokoro-82M fp32** (`af_bella`) — warmer, can be slow on some phones
  - English **Smooth**: **Piper** `en_US-lessac-medium` — faster continuous listen
  - Indonesian: **Piper** `id_ID-news_tts-medium`
- Falls back to the device TTS voice if the neural pack is missing
- Google sign-in + local progress saving
- Import `.epub`, `.txt`, or Markdown
- English and Indonesian library to start

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

Tap **Listen**. The first English book downloads the Kokoro pack (~300 MB) into app storage. On Voices you can also download **Smooth** (~67 MB) for fewer gaps. The first Indonesian book downloads Piper (~64 MB). After that, listening works in airplane mode.

Tap the caption on the mini player to switch **Hana · neural** (Kokoro/Piper) and **Device** (system TTS).

The web preview uses Kokoro in the browser. This Android app uses sherpa-onnx so playback still works with the screen locked.

See `NOTICE` for licenses and download URLs.

## Signed release

Set repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, then run the workflow with **Build signed release APK**.
