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

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press **Run**.

## Install on a phone

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Scope / not-yet

This is a demo. Deliberately out of scope: live traffic, nationwide coverage,
crowdsourced landmark contribution, business listings, offline maps, native iOS.
The landmark list in `app/src/main/java/com/wein/app/Landmark.kt` is the seed of
what would become the real, defensible dataset.
