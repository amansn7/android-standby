# Standby

iPhone-style StandBy for Android: analog clock on the left, calendar / weather on the right, battery ring, and notification banners.

**[⬇ Download Standby.apk](https://github.com/amansn7/android-standby/releases/latest/download/Standby.apk)** (Android 8.0+) — or see [Releases](https://github.com/amansn7/android-standby/releases).

## Setup on phone

1. Install `Standby.apk` (allow "install unknown apps").
2. Open it → allow location (for weather).
3. Notification-access screen opens on first launch → enable **Standby**. Long-press the clock screen to reopen it later.
4. Auto standby: Settings → Display → Screen saver → **Standby**, start "While charging".

Tap or swipe the right panel to switch calendar ↔ weather. Back/power exits.

## Build

Needs JDK 17 and Android SDK (platform 34).

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Weather: [Open-Meteo](https://open-meteo.com) (no API key).
