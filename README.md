# Wein — Lebanon landmark maps (demo)

A native Android proof-of-concept for a **landmark-native** maps app for Lebanon.
Instead of streets and addresses, you navigate by the places people actually use
("near BDL", "above ABC Achrafieh", "past Sassine Square").

This is a **pitch/demo build**, scoped to Greater Beirut.

## What it does

- Real interactive map of Beirut (MapLibre + free OpenFreeMap vector tiles — no API key).
- ~20 well-known Beirut landmarks as tappable pins.
- Tap a pin (or search a landmark) → draws a **real driving route** (OSRM) and gives
  turn-by-turn directions **phrased in landmarks**.
- Long-press a pin to change your start point.

## Tech

- 100% native Kotlin, single `MainActivity`. No accounts, no keys.
- Map: [MapLibre Native Android](https://maplibre.org/) `11.8.4`
- Tiles/style: [OpenFreeMap](https://openfreemap.org/) `liberty`
- Routing: public [OSRM](https://project-osrm.org/) demo server (fine for a demo; not for production)

## Getting it working

### Prerequisites

| Requirement | Version | Notes |
|-------------|---------|-------|
| **JDK** | 17 | Required by Android Gradle Plugin 8.11. `java -version` should report 17. |
| **Android SDK** | Platform **36**, min **26** | Installed automatically by Android Studio, or via `sdkmanager`. |
| Gradle | 8.13 | **Don't install it** — the bundled `./gradlew` wrapper downloads the right version. |

No accounts, API keys, or signing keys are needed — map tiles come from a public
URL and debug builds are signed with the auto-generated debug key.

### Fresh clone → running app

```bash
git clone git@github.com:hassanzeaiter/wein_lb_maps_android.git
cd wein_lb_maps_android
```

**Easiest — Android Studio:** open the folder. It auto-generates `local.properties`,
syncs Gradle, and you press **Run** ▶ on a connected device or emulator.

**Command line:** point Gradle at your Android SDK, then build. `local.properties`
is intentionally **not** in git (it holds a machine-specific SDK path), so create it —
or set `ANDROID_HOME` — on each machine:

```bash
# one of these:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # macOS
echo "sdk.dir=$HOME/Android/Sdk"        > local.properties     # Linux
# ...or instead: export ANDROID_HOME=$HOME/Library/Android/sdk

./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Install / run on a phone

Enable USB debugging on the device, connect it, then:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.wein.app -c android.intent.category.LAUNCHER 1   # launch it
```

The app asks for **location** permission (to place you and navigate) and, on
Android 13+, **notifications** (for the ongoing turn-by-turn notification while
navigating in the background).

### Prebuilt APK (no toolchain needed)

Every push to `main`/`develop` builds a debug APK in CI. To grab one without building:
**[Actions tab](https://github.com/hassanzeaiter/wein_lb_maps_android/actions)** →
open a green run → **Artifacts** → download `wein-debug-apk-*` → unzip → `adb install`.
Artifacts are kept for 30 days.

## Continuous integration

[`.github/workflows/android-build.yml`](.github/workflows/android-build.yml) builds
the debug APK on every push to **`main`** and **`develop`**, and on pull requests
targeting them. Feature branches are verified through their PR rather than on each push.

## Scope / not-yet

This is a demo. Deliberately out of scope: live traffic, nationwide coverage,
crowdsourced landmark contribution, business listings, offline maps, native iOS.
The landmark list in `app/src/main/java/com/wein/app/Landmark.kt` is the seed of
what would become the real, defensible dataset.
