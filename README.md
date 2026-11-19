# Standby

iPhone-style StandBy for Android: analog clock on the left, calendar / weather on the right, battery ring, and notification banners.

**[⬇ Download Standby.apk](https://github.com/amansn7/android-standby/releases/latest/download/Standby.apk)** (Android 8.0+) — or see [Releases](https://github.com/amansn7/android-standby/releases).

## Setup on phone

1. Install `Standby.apk` (allow "install unknown apps").
2. Open it once → allow **location** (weather) and **notifications**.
3. If a "full-screen notifications" screen opens → allow **Standby** (Android 14+).
4. Next launch, the notification-access screen opens → enable **Standby** (for banners). Long-press the clock screen to reopen it later.
5. Recommended: Settings → Apps → Standby → Battery → **Unrestricted**, so the charger watcher isn't killed.

**Plug in the charger → Standby opens over the lock screen** (even after a power-button lock). Unplug → it closes and the phone stays locked. A silent "Standby opens when you plug in" notification stays in the shade; that's the watcher.

Tap or swipe the right panel to switch calendar ↔ weather. Back/power exits.

Optional: Settings → Display → Screen saver → **Standby** also works on phones that have that menu.

## Build

Needs JDK 17 and Android SDK (platform 34).

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

Weather: [Open-Meteo](https://open-meteo.com) (no API key).
