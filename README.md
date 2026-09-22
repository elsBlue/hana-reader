# Hana

A calm Android ebook reader that speaks in a soft Japanese-girl voice. Built for books you would rather hear than stare at — English and Indonesian.

## What it does

- Minimal library + reader
- **Listen like music**: lock the phone, Hana keeps reading from a notification
- Default voice is Japanese female TTS (Google), slightly slower, slightly warmer
- Google sign-in + local progress saving
- Import `.txt` or `.epub`
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

Until that is added, **Continue on this phone** still works and progress is saved on the device.

## Voice

Install **Google Text-to-Speech** and the **Japanese** language pack on the device. Hana picks a female `ja-JP` voice, pitch `1.12`, rate `0.90`. Switch to **Clear** in the player if you want native English / Indonesian pronunciation.

## Signed release

Set repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, then run the workflow with **Build signed release APK**.
