# Hana

A calm Android ebook reader that reads to you in a warm female voice. Built for books you would rather hear than stare at — English and Indonesian.

## What it does

- Minimal library + reader
- **Listen like music**: lock the phone, Hana keeps reading from a notification
- Default voice is a warm local female TTS voice (not Japanese-on-English)
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

Install **Google Text-to-Speech**. Hana picks a warm female voice in the book’s language (English or Indonesian), slightly slower than conversation. Switch to **Device** in the player for a more neutral built-in voice.

The web app uses **Kokoro**, an open neural voice that actually sounds human. Android keeps on-device TTS so listening still works with the screen locked, offline.

## Signed release

Set repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, then run the workflow with **Build signed release APK**.
