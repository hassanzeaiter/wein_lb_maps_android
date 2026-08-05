package com.wein.app

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.wein.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Live, GPS-driven navigation: the route follow, the arrow/puck, the chase camera, off-route
 * reroute, voice guidance, the turn banner + ongoing notification, and the route logger.
 *
 * Owns all navigation-only state; the shared trip state (origin/currentDest/currentRoute/
 * currentMode) and the map itself live on the [activity], reached through it, along with the
 * shared map helpers (setOriginMarker/drawRoute/fitTo/geoSource/nearestLandmark) and dp/toast.
 * Extracted from MainActivity (Wave 2). Validate on a real device — the emulator's software GL
 * makes nav too laggy to judge.
 */
internal class NavigationController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private data class NavStep(val distAlong: Double, val arrow: String, val text: String)

    private var navSteps: List<NavStep> = emptyList()
    private var navPtr = 0          // index of the next maneuver to reach
    private var announcedPtr = -1   // index of the maneuver we've already spoken

    private var navListener: android.location.LocationListener? = null
    private var navPath: List<Point> = emptyList()
    private var navCum: DoubleArray = DoubleArray(0)
    private var navTotal = 0.0
    private var navCamBrg = 0.0     // eased camera heading
    private var camPrimed = false   // first fix snaps; later fixes ease
    private var navProgress = 0.0   // furthest distance travelled along the route (monotonic)
    private var offRouteFixes = 0   // consecutive fixes seen off the suggested route
    private var rerouting = false   // a reroute fetch is in flight
    private var lastRerouteMs = 0L  // throttles reroutes so we don't thrash

    // Route-logging: capture the suggestion + the real GPS track for later analysis.
    private val navTrack = ArrayList<org.json.JSONObject>()
    private var navLogStartMs = 0L
    private var navLogged = false

    // Camera-follow state (lets the user pan around like Google Maps)
    private var navigating = false
    private var followingUser = true
    private var lastPuck: LatLng? = null
    private var lastBrg = 0.0
    private var puckAnim: android.animation.ValueAnimator? = null
    // The arrow's own heading (compass degrees), smoothed and independent of the camera so
    // it points the real travel direction even when the map is panned or mid-turn.
    private var puckBrg = 0.0
    private var puckPrimed = false

    // Voice guidance
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ttsReady = false
    private var voiceOn = true

    init {
        tts = android.speech.tts.TextToSpeech(activity) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
                ttsReady = true
            }
        }
    }

    private fun dp(v: Int) = activity.dp(v)
    private fun toast(msg: String) = activity.toast(msg)

    // ---- Public API (called from MainActivity) ---------------------------

    fun isNavigating(): Boolean = navigating

    /** A user drag/zoom during nav releases auto-follow (Google-style). */
    fun onCameraMovedByGesture() {
        if (navigating && followingUser) {
            followingUser = false
            binding.recenterBtn.visibility = View.VISIBLE
        }
    }

    /** Coming back from a screen lock: re-attach the GPS listener if nav is live. */
    fun onResume() {
        if (navigating && navListener == null) activity.currentDest?.let { startLocationTracking(it) }
    }

    /** Keep GPS + guidance running while the screen locks; only drop the listener when idle. */
    fun onStop() {
        if (!navigating) stopLocationTracking()
    }

    fun dispose() {
        tts?.stop(); tts?.shutdown()
    }

    fun toggleVoice() {
        voiceOn = !voiceOn
        binding.muteBtn.setIconResource(if (voiceOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        if (!voiceOn) tts?.stop()
    }

    private fun speak(text: String) {
        if (!voiceOn || !ttsReady) return
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "wein-nav")
    }

    // ---- Navigation mode -------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        val dest = activity.currentDest ?: return

        // Real navigation needs the device's position from the GPS sensor.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            toast("Allow location, then tap Start to begin navigation.")
            return
        }

        // You navigate from where you ACTUALLY are — so route from the live fix, not from
        // whatever origin was previewed. This is what keeps the puck, steps and arrival honest.
        val lmgr = activity.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { lmgr.isProviderEnabled(it) }
        val here = providers.mapNotNull { lmgr.getLastKnownLocation(it) }.minByOrNull { it.accuracy }
        if (here == null) {
            toast("Waiting for a GPS fix… tap the locate button, then Start.")
            activity.requestLocate(silent = true)
            return
        }
        activity.origin = Landmark("Your location", "you", here.latitude, here.longitude)
        activity.setOriginMarker(activity.origin)

        ensureNotifPermission()
        enterNavUi(true)
        navigating = true
        followingUser = true
        camPrimed = false
        puckPrimed = false
        navProgress = 0.0
        navPtr = 0
        announcedPtr = -1
        offRouteFixes = 0
        rerouting = false
        binding.recenterBtn.visibility = View.GONE
        binding.muteBtn.setIconResource(if (voiceOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        binding.navTurn.text = "Getting your route…"
        binding.navDist.text = ""

        val mode = activity.currentMode
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRoute(activity.origin, dest, mode) }
            if (!navigating) return@launch            // user pressed End while we fetched
            if (result == null || result.geometry.size < 2) {
                toast("Couldn't get a route from your location. Check your connection.")
                end()
                return@launch
            }
            activity.currentRoute = result
            navPath = result.geometry
            navCum = cumulative(navPath)
            navTotal = navCum.last()
            buildNavSteps(result, dest)
            activity.drawRoute(navPath)
            navTrack.clear()
            navLogStartMs = System.currentTimeMillis()
            navLogged = false
            speak("Starting ${mode.label.lowercase(java.util.Locale.US)} to ${dest.name}.")
            startLocationTracking(dest)
            // Go foreground so fixes keep arriving with the app backgrounded / screen locked.
            NavService.start(activity, "Navigating to ${dest.name}", "Starting…")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationTracking(dest: Landmark) {
        val lm = activity.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            toast("Turn on location (GPS) to navigate.")
            return
        }
        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) = onNavFix(location, dest)
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        navListener = listener
        // Fast updates (1 s / any distance) so the puck tracks the sensor closely.
        for (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, listener, activity.mainLooper)
        // Seed immediately with the best last-known fix so the camera flies in at once.
        providers.mapNotNull { lm.getLastKnownLocation(it) }
            .minByOrNull { it.accuracy }
            ?.let { onNavFix(it, dest) }
    }

    private fun stopLocationTracking() {
        navListener?.let {
            val lm = activity.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
            lm.removeUpdates(it)
        }
        navListener = null
    }

    /** One GPS fix during navigation: snap to the route, update puck/camera/banner/voice. */
    private fun onNavFix(location: android.location.Location, dest: Landmark) {
        if (!navigating || navPath.size < 2) return
        val raw = LatLng(location.latitude, location.longitude)

        // Drop obviously useless fixes so the puck doesn't teleport on noise.
        if (location.hasAccuracy() && location.accuracy > 60f) return
        val snap = snapToRoute(navPath, navCum, raw)
        // Off the suggested route — the driver took a different road, or OSRM's line simply
        // doesn't match the real road. The old code froze the puck here (it kept dropping every
        // fix while the camera was following, so the cursor only moved when you tapped Re-center).
        // Instead: keep the cursor LIVE on the real GPS position, and once we're clearly off the
        // line, rebuild the route from here so guidance follows the road actually driven.
        if (snap.offRoute > OFF_ROUTE_METERS) {
            offRouteFixes++
            if (location.hasBearing() && location.speed > 1.0) lastBrg = location.bearing.toDouble()
            updatePuckHeading(location, fallback = lastBrg)
            updatePuck(raw, puckBrg)
            lastPuck = raw
            if (followingUser) {
                navCamBrg = if (!camPrimed) lastBrg else lerpAngle(navCamBrg, lastBrg, 0.35)
                camPrimed = true
                val aim = destinationPoint(raw.latitude, raw.longitude, navCamBrg, 45.0)
                activity.map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(aim).zoom(activity.currentMode.camZoom).tilt(58.0).bearing(navCamBrg).build()
                    ), 950,
                )
            }
            if (offRouteFixes >= OFF_ROUTE_FIXES_BEFORE_REROUTE ||
                snap.offRoute > OFF_ROUTE_HARD_METERS
            ) reroute(raw, dest)
            return
        }
        offRouteFixes = 0

        // Progress only ever moves forward — a noisy fix can't drag us back or skip ahead
        // to a nearer part of the polyline. This is what stops the random anchor jumping.
        navProgress = navProgress.coerceAtLeast(snap.distAlong).coerceAtMost(navTotal)
        val dist = navProgress
        val pos = positionAt(navPath, navCum, dist)

        // Record the raw fix + where it snapped, for offline analysis of the suggestion.
        navTrack.add(org.json.JSONObject().apply {
            put("t", System.currentTimeMillis() - navLogStartMs)
            put("lat", raw.latitude)
            put("lng", raw.longitude)
            put("acc", if (location.hasAccuracy()) location.accuracy.toDouble() else org.json.JSONObject.NULL)
            put("brg", if (location.hasBearing()) location.bearing.toDouble() else org.json.JSONObject.NULL)
            put("spd", if (location.hasSpeed()) location.speed.toDouble() else org.json.JSONObject.NULL)
            put("offRoute", snap.offRoute)
            put("progress", dist)
        })

        // Heading: trust the device course only when actually moving; otherwise face along
        // the route so the map still orients sensibly when stopped.
        val heading =
            if (location.hasBearing() && location.speed > 1.0) location.bearing.toDouble()
            else snap.bearing

        updatePuckHeading(location, fallback = snap.bearing)
        updatePuck(pos, puckBrg)
        lastPuck = pos
        lastBrg = heading

        if (followingUser) {
            navCamBrg = if (!camPrimed) heading else lerpAngle(navCamBrg, heading, 0.35)
            camPrimed = true
            val aim = destinationPoint(pos.latitude, pos.longitude, navCamBrg, 45.0)
            val cam = CameraPosition.Builder()
                .target(aim).zoom(activity.currentMode.camZoom).tilt(58.0).bearing(navCamBrg).build()
            // Animate between fixes (~1 s apart) so movement glides instead of jumping.
            activity.map?.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 950)
        }

        updateNavBanner(dist, navTotal, activity.currentMode)
        maybeAnnounce(dist, activity.currentMode)

        // Arrived only once we've genuinely progressed to the end of the route AND are
        // physically near the destination — never on the first fix.
        val remaining = navTotal - dist
        val crowFlies = haversine(raw.latitude, raw.longitude, dest.lat, dest.lng)
        if (remaining <= 15.0 && crowFlies <= 40.0) {
            onArrived(dest)
            stopLocationTracking()
        }
    }

    /**
     * The driver has left the suggested route — fetch a fresh route from the live position to
     * the destination and swap it in, so the puck, steps and voice follow the road actually
     * taken. Throttled and single-flight so a persistent map/road mismatch retries calmly
     * (every [REROUTE_COOLDOWN_MS]) instead of thrashing; the cursor stays live throughout.
     */
    private fun reroute(from: LatLng, dest: Landmark) {
        if (rerouting) return
        if (System.currentTimeMillis() - lastRerouteMs < REROUTE_COOLDOWN_MS) return
        rerouting = true
        lastRerouteMs = System.currentTimeMillis()
        binding.navTurn.text = "Rerouting…"
        speak("Rerouting.")
        val mode = activity.currentMode
        val here = Landmark("Your location", "you", from.latitude, from.longitude)
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRoute(here, dest, mode) }
            rerouting = false
            if (!navigating) return@launch
            if (result == null || result.geometry.size < 2) return@launch  // keep old route; retry later
            activity.origin = here
            activity.setOriginMarker(here)
            activity.currentRoute = result
            navPath = result.geometry
            navCum = cumulative(navPath)
            navTotal = navCum.last()
            buildNavSteps(result, dest)
            activity.drawRoute(navPath)
            navProgress = 0.0
            navPtr = 0
            announcedPtr = -1
            offRouteFixes = 0
            camPrimed = false        // re-lock the camera heading on the next fix
        }
    }

    fun end() {
        // Save whatever we have (suggestion + partial track) before tearing down.
        val d = activity.currentDest; val r = activity.currentRoute
        if (!navLogged && d != null && r != null && navPath.size >= 2) saveRouteLog(d, r, completed = false)
        stopLocationTracking()
        NavService.stop(activity)
        navigating = false
        binding.recenterBtn.visibility = View.GONE
        tts?.stop()
        clearPuck()
        enterNavUi(false)
        activity.currentDest?.let { activity.fitTo(activity.origin.latLng, it.latLng) }  // resets tilt/bearing to north-up
    }

    private fun onArrived(dest: Landmark) {
        navigating = false
        NavService.stop(activity)
        binding.recenterBtn.visibility = View.GONE
        binding.navArrow.text = "●"
        binding.navDist.text = "Arrived"
        binding.navTurn.text = "You've reached ${arrivalName(dest)}"
        binding.navRemaining.text = "0 m left"
        speak("You have arrived at ${arrivalName(dest)}.")
        activity.currentRoute?.let { if (!navLogged) saveRouteLog(dest, it, completed = true) }
        toast("Arrived at ${dest.name}")
    }

    /**
     * Persist the navigation session to a JSON file on the device: the OSRM suggestion
     * (geometry, distance/duration, raw maneuvers, our landmark itinerary) plus the real
     * GPS track. Pull with adb afterwards to analyse whether the suggestions look good.
     */
    private fun saveRouteLog(dest: Landmark, result: RouteResult, completed: Boolean) {
        try {
            val dir = java.io.File(activity.filesDir, "routes").apply { mkdirs() }
            val now = java.util.Date()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(now)
            val slug = dest.name.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(28)
            val file = java.io.File(dir, "route-$stamp-$slug.json")

            val geom = org.json.JSONArray()
            for (p in result.geometry) geom.put(org.json.JSONArray().put(p.longitude()).put(p.latitude()))
            val osrm = org.json.JSONArray()
            for (s in result.steps) osrm.put(org.json.JSONObject().apply {
                put("type", s.type); put("modifier", s.modifier); put("name", s.name)
                put("lat", s.lat); put("lng", s.lng)
            })
            val itin = org.json.JSONArray()
            for (line in buildItinerary(dest, result.steps)) itin.put(line)
            val track = org.json.JSONArray()
            for (pt in navTrack) track.put(pt)

            val json = org.json.JSONObject().apply {
                put("savedAt", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(now))
                put("completed", completed)
                put("mode", activity.currentMode.label)
                put("origin", org.json.JSONObject().put("name", activity.origin.name).put("lat", activity.origin.lat).put("lng", activity.origin.lng))
                put("destination", org.json.JSONObject().apply {
                    put("name", dest.name); put("kind", dest.kind); put("lat", dest.lat); put("lng", dest.lng)
                })
                put("suggestion", org.json.JSONObject().apply {
                    put("distanceMeters", result.distanceMeters)
                    put("durationSeconds", result.durationSeconds)
                    put("geometry", geom)
                    put("osrmSteps", osrm)
                    put("landmarkSteps", itin)
                })
                put("trackPoints", navTrack.size)
                put("track", track)
            }
            file.writeText(json.toString(2))
            navLogged = true
            android.util.Log.i("Wein", "Route log saved: ${file.absolutePath} (${navTrack.size} pts)")
            toast("Route saved: ${file.name}")
        } catch (e: Exception) {
            android.util.Log.e("Wein", "save route log failed", e)
        }
    }

    /** Resume auto-follow, gliding back to the puck. */
    fun recenter() {
        binding.recenterBtn.visibility = View.GONE
        camPrimed = false          // let the next GPS fix re-lock heading to the puck
        val pos = lastPuck
        if (pos == null) { followingUser = true; return }
        val cam = CameraPosition.Builder()
            .target(destinationPoint(pos.latitude, pos.longitude, lastBrg, 45.0))
            .zoom(activity.currentMode.camZoom).tilt(58.0).bearing(lastBrg).build()
        activity.map?.animateCamera(
            CameraUpdateFactory.newCameraPosition(cam), 600,
            object : MapLibreMap.CancelableCallback {
                override fun onFinish() { followingUser = true }
                override fun onCancel() { followingUser = true }
            },
        )
    }

    /**
     * Announce ONE maneuver at a time: speak the next turn once it's within lead
     * distance, and don't move on to the following turn until we've passed this one.
     * (Prevents "in 160 m turn right, in 160 m turn left" firing together.)
     */
    private fun maybeAnnounce(dist: Double, mode: TravelMode) {
        val lead = if (mode == TravelMode.WALK) 40.0 else 160.0
        // Skip the "Arrive" step (spoken by onArrived) and anything already behind us.
        while (navPtr < navSteps.size &&
            (navSteps[navPtr].text.startsWith("Arrive") || navSteps[navPtr].distAlong <= dist)
        ) {
            if (navSteps[navPtr].distAlong <= dist && announcedPtr < navPtr &&
                !navSteps[navPtr].text.startsWith("Arrive")
            ) {
                // A turn we somehow reached without announcing — say it now, briefly.
                speak("${navSteps[navPtr].text}.")
            }
            navPtr++
        }
        val step = navSteps.getOrNull(navPtr) ?: return
        val remaining = step.distAlong - dist
        if (remaining <= lead && announcedPtr != navPtr) {
            announcedPtr = navPtr
            val rounded = (Math.round(remaining / 10.0) * 10).toInt()
            speak(if (rounded > 10) "In $rounded meters, ${step.text}." else "${step.text}.")
        }
    }

    private fun buildNavSteps(route: RouteResult, dest: Landmark) {
        val path = route.geometry
        val cum = cumulative(path)
        navSteps = route.steps.mapNotNull { s ->
            if (s.type == "depart") return@mapNotNull null
            val near = activity.nearestLandmark(LatLng(s.lat, s.lng), setOf(activity.origin.name, dest.name), 450.0)
            val turn = turnPhrase(s.type, s.modifier)
            // A plain "continue/straight" with no landmark to cue on isn't an event — drop it.
            if (s.type != "arrive" && near == null && turn.startsWith("Continue")) {
                return@mapNotNull null
            }
            val dist = distAlong(path, cum, s.lat, s.lng)
            val text = when {
                s.type == "arrive" -> "Arrive at ${arrivalName(dest)}"
                near != null && turn.startsWith("Continue") -> "Continue past ${near.name}"
                near != null -> "$turn near ${near.name}"
                else -> turn
            }
            NavStep(dist, arrowFor(s.type, s.modifier), text)
        }.sortedBy { it.distAlong }
    }

    private fun updateNavBanner(dist: Double, total: Double, mode: TravelMode) {
        val next = navSteps.firstOrNull { it.distAlong > dist + 2.0 } ?: navSteps.lastOrNull()
        if (next != null) {
            binding.navArrow.text = next.arrow
            binding.navTurn.text = next.text
            val d = (next.distAlong - dist).coerceAtLeast(0.0)
            binding.navDist.text = if (next.text.startsWith("Arrive")) "In ${formatDist(d)}" else "In ${formatDist(d)}"
        }
        binding.navRemaining.text = "${formatDist((total - dist).coerceAtLeast(0.0))} left · ${mode.label}"
        updateNavNotification()
    }

    private fun arrowFor(type: String, modifier: String): String = when {
        type == "arrive" -> "●"
        type == "roundabout" || type == "rotary" -> "↻"
        modifier.contains("left") -> "↰"
        modifier.contains("right") -> "↱"
        modifier == "uturn" -> "⤶"
        else -> "↑"
    }

    private fun enterNavUi(on: Boolean) {
        binding.navBanner.visibility = if (on) View.VISIBLE else View.GONE
        binding.navBar.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            activity.sheet.isHideable = true
            activity.sheet.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            activity.sheet.isHideable = false
            activity.sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        binding.locateFab.visibility = if (on) View.GONE else View.VISIBLE
        binding.listFab.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun setPuckAt(pos: LatLng, bearing: Double) {
        val f = Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude))
        f.addNumberProperty("bearing", bearing)
        activity.geoSource(MainActivity.SRC_PUCK)?.setGeoJson(f)
    }

    /**
     * Update the arrow's heading: use the device course while actually moving; when stopped or
     * creeping (GPS bearing is meaningless at low speed) hold the last direction rather than
     * spinning on noise. [fallback] orients the very first fix before we have a course.
     */
    private fun updatePuckHeading(location: android.location.Location, fallback: Double) {
        val moving = location.hasBearing() && location.speed > 1.0
        puckBrg = when {
            !puckPrimed -> if (moving) location.bearing.toDouble() else fallback
            moving -> lerpAngle(puckBrg, location.bearing.toDouble(), 0.5)
            else -> puckBrg
        }
        puckPrimed = true
    }

    /**
     * Glide the puck from its last position to the new fix over ~1 s (fixes arrive ~1 s
     * apart), so the anchor slides with the car instead of teleporting each update. The
     * arrow's [bearing] is held for the glide and refreshed on the next fix.
     */
    private fun updatePuck(pos: LatLng, bearing: Double) {
        val from = lastPuck
        puckAnim?.cancel()
        if (from == null) { setPuckAt(pos, bearing); return }
        puckAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 950
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction.toDouble()
                setPuckAt(
                    LatLng(
                        from.latitude + (pos.latitude - from.latitude) * t,
                        from.longitude + (pos.longitude - from.longitude) * t,
                    ),
                    bearing,
                )
            }
            start()
        }
    }

    private fun clearPuck() {
        puckAnim?.cancel()
        puckAnim = null
        activity.geoSource(MainActivity.SRC_PUCK)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    /**
     * Build a clean *landmark-only* itinerary — the way people here actually give
     * directions: only the well-known places, not every micro-turn or street name.
     */
    fun renderSteps(dest: Landmark, steps: List<Maneuver>) {
        val container = binding.stepsContainer
        container.removeAllViews()
        buildItinerary(dest, steps).forEachIndexed { i, phrase ->
            container.addView(stepRow(i + 1, phrase))
        }
    }

    /** The clean landmark-only itinerary (also written to the saved route log for analysis). */
    private fun buildItinerary(dest: Landmark, steps: List<Maneuver>): List<String> {
        val phrases = ArrayList<String>()
        phrases.add("Start at ${activity.origin.name}.")
        var lastLandmark = activity.origin.name
        steps.forEachIndexed { i, step ->
            if (i == 0 || step.type == "arrive") return@forEachIndexed
            val near = activity.nearestLandmark(LatLng(step.lat, step.lng), setOf(activity.origin.name, dest.name), 450.0)
                ?: return@forEachIndexed
            if (near.name == lastLandmark) return@forEachIndexed   // don't repeat the same landmark
            val turn = turnPhrase(step.type, step.modifier)
            phrases.add(
                if (turn.startsWith("Continue")) "Continue past ${near.name}."
                else "$turn near ${near.name}."
            )
            lastLandmark = near.name
        }
        phrases.add("Arrive at ${arrivalName(dest)}.")
        return phrases
    }

    private fun stepRow(number: Int, text: String): View {
        val tv = TextView(activity)
        tv.text = "$number.  $text"
        tv.textSize = 14.5f
        tv.setTextColor(Color.parseColor("#202124"))
        tv.setPadding(0, dp(7), 0, dp(7))
        tv.gravity = Gravity.START
        return tv
    }

    /** A landmark reads by name; an arbitrary dropped point reads as "your destination". */
    private fun arrivalName(dest: Landmark): String =
        if (dest.kind == "point") "your destination" else dest.name

    private fun turnPhrase(type: String, modifier: String): String = when (type) {
        "depart" -> "Continue"
        "arrive" -> "Arrive"
        "roundabout", "rotary" -> "Take the roundabout"
        "merge" -> "Merge"
        "fork" -> if (modifier.contains("left")) "Keep left" else "Keep right"
        "end of road" -> if (modifier.contains("left")) "Turn left" else "Turn right"
        else -> when (modifier) {
            "left" -> "Turn left"
            "right" -> "Turn right"
            "slight left" -> "Bear left"
            "slight right" -> "Bear right"
            "sharp left" -> "Sharp left"
            "sharp right" -> "Sharp right"
            "uturn" -> "Make a U-turn"
            "straight" -> "Continue straight"
            else -> "Continue"
        }
    }

    // ---- Foreground navigation notification ------------------------------

    private fun ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            activity.notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Push the current banner (turn + distances) into the ongoing FGS notification. */
    private fun updateNavNotification() {
        if (!navigating) return
        val title = binding.navTurn.text?.toString()?.takeIf { it.isNotBlank() } ?: "Navigating"
        val text = listOf(
            binding.navDist.text?.toString().orEmpty(),
            binding.navRemaining.text?.toString().orEmpty(),
        ).filter { it.isNotBlank() }.joinToString("  ·  ")
        NavService.start(activity, title, text)
    }

    private companion object {
        // Off-route handling: how far off the suggested line counts as "off-route", and how
        // many consecutive off-route fixes to tolerate before rebuilding the route from here.
        private const val OFF_ROUTE_METERS = 60.0
        private const val OFF_ROUTE_HARD_METERS = 120.0
        private const val OFF_ROUTE_FIXES_BEFORE_REROUTE = 3
        private const val REROUTE_COOLDOWN_MS = 12_000L
    }
}
