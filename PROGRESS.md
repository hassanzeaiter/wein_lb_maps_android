# Wein — progress & plan (handoff)

**Wein** (وين = "where?") — a native Android **places directory for Lebanon** (local Yelp/Google-Places) whose defensible wedge is **landmark-native navigation** (people here navigate by landmarks — "near BDL", "above ABC Achrafieh" — not street addresses). Places + search are the *product*; the map + turn-by-turn is the *"Directions" tool*. Monetization: ratings, reviews, photos, promoted listings, business claims, taxi bookings. Pitch/demo to raise funding — MVP-first, Greater Beirut only. Not production.

## Build / run
- 100% native Kotlin, single `MainActivity` (view-swapping, no fragments). MapLibre `11.8.4`; OpenFreeMap **`positron` (grayscale)** tiles, no API key. Routing = public OSRM (FOSSGIS). compileSdk 36 / minSdk 26 / targetSdk 35. JDK = Android Studio JBR.
- `export JAVA_HOME="/Users/hassanzeaiter/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- `./gradlew assembleDebug --offline` → `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk`
- Devices: emulator `emulator-5554` (mock GPS: `adb -s emulator-5554 emu geo fix 35.5018 33.8938`) and Pixel 6a `28311JEGR06655`. **Do NOT run navigation on the emulator** (SwiftShader software GL = very laggy); nav is smooth on the Pixel. Static UI is fine on the emulator.
- **Gotchas:** (1) OSRM (FOSSGIS) rejects the default Dalvik User-Agent → must send a custom `User-Agent` header. (2) `CollapsingToolbarLayout` + edge-to-edge insets behaved differently per device → replaced with a custom scroll-listener header.

## Data models
- `app/src/main/java/com/wein/app/Place.kt`: **`PLACES`** (~33 seed Beirut places) — the directory. `Place(name, category:PlaceCategory, rating, reviews, price, area, landmark, lat, lng, promoted, openNow)`. `PlaceCategory` enum carries a `glyph` drawable. `PLACE_CHIPS` = filter chips.
- `Landmark.kt`: **`BEIRUT_LANDMARKS`** (~22) — internal nav-cue graph for turn phrasing (`nearestLandmark`) only; NOT the directory.

## Built (all working, monochrome UI)
- **Bottom nav:** Explore / Contribute / Profile. Explore = home (list + chips + search, Promoted-first). Header **Map** button → full-screen map (bottom nav hidden, back-to-list FAB). Contribute/Profile = MVP stubs.
- **Map:** grayscale positron + **gray 3D buildings** re-added as a `FillExtrusionLayer` (source `openmaptiles`, source-layer `building`, `render_height`/`render_min_height`, inserted below `waterway_line_label`). MapLibre logo + on-map attribution OFF; **© OpenStreetMap** credit shown in Profile instead.
- **Place detail (Phase B):** custom **parallax header** (`FrameLayout > NestedScrollView > [header + content]` with a fixed `detailTopBar`: back always pinned, `detailTopTitle` + white bg fade in on scroll, `detailHeader.translationY = scrollY*0.5`). Big `detailName` at content top. Sections: Promoted, name, rating/price/open, landmark location, **Directions/Call/Save/Share**, About, Reviews (avatars+stars), Photos strip, **Claim & Promote**. `openPlace→showPlaceDetail`; back via `detailBack`/hardware back.
- **Directions sheet:** draggable peek/expand, **X to dismiss** (`closeSheet`), not swipe-dismissable when a place is selected.
- **Nav:** constant-speed sim puck (Drive ≈8 m/s, Walk ≈1.4 m/s; **Transit removed**), damped 60fps chase cam (fly-in + look-ahead), turn banner, **voice** (TextToSpeech, one turn at a time), **free pan during nav** + **Re-center** button. Auto-locate on startup (origin = "Your location").

## NEXT: Phase C — put the directory PLACES on the map
Map currently shows `BEIRUT_LANDMARKS`; it should show **PLACES** as pins, tapping a pin opens the **same place detail**. Keep `BEIRUT_LANDMARKS` internal for turn phrasing. In `MainActivity.kt`:
1. **addMapLayers:** build `SRC_LANDMARKS` features from `PLACES` (not `BEIRUT_LANDMARKS`); feature `name`=place.name + an `icon` per `PlaceCategory` (register `makeMarker(PIN_COLOR, category.glyph)` as `"place-<category.name>"`); keep the label layer.
2. **wireMapGestures:** map click → new `nearestPlace(latLng, maxMeters)` (haversine over `PLACES`) → `showPlaceDetail(place)` (replaces `nearestLandmark→routeTo`). Decide long-press behavior.
3. **Back target:** add `detailFromMap` flag — from a map pin, `hidePlaceDetail()` returns to the **map** (bottom nav stays hidden, map overlays restored); from the list, keep current behavior. Directions still works via `routeTo(Landmark(place.name,"place",lat,lng))`. ~30 pins → no clustering.

## Monetization ideas (future)
- Promoted listings (already sorted first) + **Claim & Promote** business tools.
- **Taxi mode:** add **Taxi** to the mode selector next to Drive/Walk; partner with a **taxi office** (commission per booking / booking fee). Flow: place → Directions → Taxi → fare estimate + "Book a taxi" (call/deep-link/API). Reuses the routing distance/time. (Takes the slot the removed Transit mode had.)
- Later: real photos, real reviews/claim flows, more categories/coverage, Arabic UI, **Live nav** (real FusedLocation GPS + off-route reroute) with an in-app **.txt nav log** (app-instructed vs. actually-driven) for tuning.
