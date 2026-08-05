package com.wein.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import com.wein.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var map: MapLibreMap? = null
    private var style: Style? = null

    // Default trip start; changed by long-pressing a pin or using current location.
    private var origin: Landmark = BEIRUT_LANDMARKS.first { it.name == "AUB Main Gate" }
    private var suppressWatcher = false

    // Routing / navigation state
    private var currentMode = TravelMode.DRIVE
    private var currentDest: Landmark? = null
    private var currentRoute: RouteResult? = null
    private var navSteps: List<NavStep> = emptyList()
    private var navPtr = 0        // index of the next maneuver to reach
    private var announcedPtr = -1 // index of the maneuver we've already spoken

    // In-app taxi (ride-hailing) flow — self-contained; see TaxiController.
    private val taxi by lazy { TaxiController(this, binding) }

    // Place-detail screen — self-contained; see PlaceDetailController.
    private val placeDetail by lazy { PlaceDetailController(this, binding) }

    private lateinit var sheet: BottomSheetBehavior<*>
    private var currentChip = 0   // selected Explore category chip
    private var didAutoLocate = false

    // Live navigation driven by the device GPS (no simulation).
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

    // Nav camera-follow state (lets the user pan around like Google Maps)
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

    private data class NavStep(val distAlong: Double, val arrow: String, val text: String)

    private val locationPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) useCurrentLocation()
            else toast("Location permission is needed to use your position.")
        }

    // Notifications are optional — the foreground service runs either way; without the
    // permission the ongoing nav notification just isn't shown.
    private val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    // The "End" action on the nav notification broadcasts here so we tear the session down
    // through the same path as the in-app End button.
    private val endNavReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (navigating) endNavigation()
        }
    }

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        private val BEIRUT = LatLng(33.8925, 35.5040)

        private const val SRC_PLACES = "places"
        private const val SRC_ROUTE = "route"
        private const val SRC_ORIGIN = "origin"
        private const val SRC_DEST = "dest"
        private const val SRC_PUCK = "puck"
        private const val PUCK_IMG = "puck-arrow"

        private const val ROUTE_COLOR = "#202124"
        private const val ROUTE_CASING = "#FFFFFF"
        private const val ORIGIN_COLOR = "#FFFFFF"   // white "start" dot (black ring)
        private const val DEST_COLOR = "#202124"     // black destination dot
        private const val PIN_COLOR = "#3C4043"      // one dark-gray for all pins

        // Off-route handling: how far off the suggested line counts as "off-route", and how
        // many consecutive off-route fixes to tolerate before rebuilding the route from here.
        // A big single jump (HARD) reroutes at once, so we don't sit ~3 s off an obviously
        // wrong line waiting for the fix count.
        private const val OFF_ROUTE_METERS = 60.0
        private const val OFF_ROUTE_HARD_METERS = 120.0
        private const val OFF_ROUTE_FIXES_BEFORE_REROUTE = 3
        private const val REROUTE_COOLDOWN_MS = 12_000L
    }

    /** Landmark category → glyph (all pins share one dark-gray colour; the glyph differentiates). */
    private data class Cat(val key: String, val color: String, val glyph: Int?)

    private val allCats = listOf(
        Cat("shopping", PIN_COLOR, R.drawable.ic_cat_shopping),
        Cat("education", PIN_COLOR, R.drawable.ic_cat_school),
        Cat("health", PIN_COLOR, R.drawable.ic_cat_health),
        Cat("transport", PIN_COLOR, R.drawable.ic_cat_transport),
        Cat("water", PIN_COLOR, R.drawable.ic_cat_water),
        Cat("worship", PIN_COLOR, null),
        Cat("place", PIN_COLOR, null),
    )

    private fun catKeyFor(kind: String): String = when (kind) {
        "mall" -> "shopping"
        "university" -> "education"
        "hospital" -> "health"
        "station", "junction" -> "transport"
        "seaside" -> "water"
        "mosque", "church" -> "worship"
        else -> "place"   // square, museum, bank, street
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A navigation app should stay awake while it's on screen.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeInsets()
        sheet = BottomSheetBehavior.from(binding.directionsCard)
        sheet.state = BottomSheetBehavior.STATE_HIDDEN

        binding.mapView.onCreate(savedInstanceState)
        binding.mapView.getMapAsync { m ->
            map = m
            // Clean map: no MapLibre logo, no on-map attribution icon.
            // OSM credit is shown in Profile instead (license-compliant, off the map).
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(BEIRUT).zoom(12.6).build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { loaded ->
                style = loaded
                addMapLayers(loaded)
                wireMapGestures(m)
                if (!didAutoLocate) {
                    didAutoLocate = true
                    requestLocate(silent = true)   // start centered on the user, not AUB
                }
            }
        }

        setupSearch()
        binding.locateFab.setOnClickListener { requestLocate() }
        binding.btnDrive.setOnClickListener { setMode(TravelMode.DRIVE) }
        binding.btnWalk.setOnClickListener { setMode(TravelMode.WALK) }
        binding.btnTaxi.setOnClickListener { setMode(TravelMode.TAXI) }
        binding.startNavBtn.setOnClickListener { startNavigation() }
        binding.endNavBtn.setOnClickListener { endNavigation() }
        binding.muteBtn.setOnClickListener { toggleVoice() }

        // Explore (places directory) is the home; the map is the "Directions" tool.
        binding.topContainer.visibility = View.GONE   // Explore's search replaces the map search
        binding.mapFab.setOnClickListener { showMap() }
        binding.listFab.setOnClickListener { showExplore() }
        binding.closeSheetBtn.setOnClickListener { closeSheet() }
        binding.recenterBtn.setOnClickListener { recenter() }
        binding.detailBack.setOnClickListener { placeDetail.hide() }
        binding.detailScroll.setOnScrollChangeListener(
            androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val a = (scrollY / dp(150).toFloat()).coerceIn(0f, 1f)
                binding.detailTopBar.setBackgroundColor(Color.argb((a * 255).toInt(), 255, 255, 255))
                binding.detailTopTitle.alpha = a
                binding.detailHeader.translationY = scrollY * 0.5f   // parallax
            }
        )
        binding.claimBtn.setOnClickListener { toast("Claim & Promote — coming soon") }
        binding.writeReviewBtn.setOnClickListener { toast("Write a review — coming soon") }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_explore -> showExplore()
                R.id.tab_contribute -> showContribute()
                R.id.tab_profile -> showProfile()
            }
            true
        }
        setupExplore()
        setupContribute()
        setupProfile()
        showExplore()

        tts = android.speech.tts.TextToSpeech(this) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                tts?.language = java.util.Locale.US
                ttsReady = true
            }
        }

        androidx.core.content.ContextCompat.registerReceiver(
            this, endNavReceiver,
            android.content.IntentFilter(NavService.ACTION_END_NAV),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun toggleVoice() {
        voiceOn = !voiceOn
        binding.muteBtn.setIconResource(if (voiceOn) R.drawable.ic_volume_up else R.drawable.ic_volume_off)
        if (!voiceOn) tts?.stop()
    }

    private fun speak(text: String) {
        if (!voiceOn || !ttsReady) return
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "wein-nav")
    }

    // ---- Current location ------------------------------------------------

    private fun requestLocate(silent: Boolean = false) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) useCurrentLocation(silent)
        else locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun useCurrentLocation(silent: Boolean = false) {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { lm.isProviderEnabled(it) }
        if (providers.isEmpty()) {
            if (!silent) toast("Turn on location (or set the emulator's location) first.")
            return
        }
        // Use the best last-known fix immediately, if any.
        providers.mapNotNull { lm.getLastKnownLocation(it) }
            .minByOrNull { it.accuracy }
            ?.let { setLocatedOrigin(it) }
        // Then ask for one fresh fix.
        if (!silent) toast("Locating you…")
        lm.requestLocationUpdates(
            providers.first(), 0L, 0f,
            object : android.location.LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    setLocatedOrigin(location)
                    lm.removeUpdates(this)
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in API 29")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            },
            mainLooper,
        )
    }

    private fun setLocatedOrigin(loc: android.location.Location) {
        origin = Landmark("Your location", "you", loc.latitude, loc.longitude)
        setOriginMarker(origin)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.5))
    }

    // ---- Map setup -------------------------------------------------------

    private fun addMapLayers(style: Style) {
        // Gray 3D buildings on the monochrome (positron) base — keeps depth for nav.
        try {
            val buildings = FillExtrusionLayer("3d-buildings", "openmaptiles")
            buildings.sourceLayer = "building"
            buildings.minZoom = 14f
            buildings.setProperties(
                PropertyFactory.fillExtrusionColor("#D3D6DA"),
                PropertyFactory.fillExtrusionHeight(
                    Expression.coalesce(Expression.get("render_height"), Expression.literal(6.0))
                ),
                PropertyFactory.fillExtrusionBase(
                    Expression.coalesce(Expression.get("render_min_height"), Expression.literal(0.0))
                ),
                PropertyFactory.fillExtrusionOpacity(0.92f),
            )
            style.addLayerBelow(buildings, "waterway_line_label")
        } catch (e: Exception) {
            android.util.Log.e("Wein", "3d buildings", e)
        }

        // A marker image per place category (one dark-gray pin, the glyph differentiates).
        for (cat in PlaceCategory.values()) {
            style.addImage("place-${cat.name}", makeMarker(PIN_COLOR, cat.glyph))
        }

        // Our directory places, as tappable pins. Each feature carries its category so
        // the map picks the right glyph (these are the real listings, not the landmarks —
        // landmarks stay behind the scenes as the vocabulary for turn directions).
        val features = PLACES.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("name", p.name)
                addStringProperty("icon", "place-${p.category.name}")
            }
        }
        style.addSource(GeoJsonSource(SRC_PLACES, FeatureCollection.fromFeatures(features)))
        style.addSource(GeoJsonSource(SRC_ROUTE))
        style.addSource(GeoJsonSource(SRC_ORIGIN))
        style.addSource(GeoJsonSource(SRC_DEST))

        // Route: a white casing under a solid blue line (premium map look).
        style.addLayer(
            LineLayer("route-casing", SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(ROUTE_CASING),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            LineLayer("route-line", SRC_ROUTE).withProperties(
                PropertyFactory.lineColor(ROUTE_COLOR),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        style.addLayer(
            SymbolLayer("place-pins", SRC_PLACES).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            )
        )
        style.addLayer(
            SymbolLayer("place-labels", SRC_PLACES).withProperties(
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(11.5f),
                PropertyFactory.textColor("#202124"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.6f),
                PropertyFactory.textOffset(arrayOf(0f, 1.6f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(true),
            )
        )
        style.addLayer(
            CircleLayer("origin-dot", SRC_ORIGIN).withProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor(ORIGIN_COLOR),
                PropertyFactory.circleStrokeColor("#202124"),
                PropertyFactory.circleStrokeWidth(4f),
            )
        )
        style.addLayer(
            CircleLayer("dest-dot", SRC_DEST).withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(DEST_COLOR),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )

        // Navigation puck (arrow that follows the route). Viewport-aligned so it always
        // points "up" while the map rotates to the travel heading — like Google's chevron.
        AppCompatResources.getDrawable(this, R.drawable.ic_puck)?.let { d ->
            style.addImage(PUCK_IMG, drawableToBitmap(d, dp(48)))
        }
        style.addSource(GeoJsonSource(SRC_PUCK))
        style.addLayer(
            SymbolLayer("puck-layer", SRC_PUCK).withProperties(
                PropertyFactory.iconImage(PUCK_IMG),
                PropertyFactory.iconSize(1.0f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                // Rotate with the map to the feature's own heading, so the arrow always points
                // the true travel direction (not just "up") regardless of the camera bearing;
                // pitch-align to the viewport so it stays crisp and unforeshortened under tilt.
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotate(Expression.get("bearing")),
            )
        )
        setOriginMarker(origin)
    }

    private fun wireMapGestures(m: MapLibreMap) {
        // Tap a landmark pin → route to it. Tap anywhere else → drop a destination
        // there and route to that point (directions aren't limited to our landmarks).
        m.addOnMapClickListener { point ->
            routeTo(placeAtTap(m, point)?.toLandmark() ?: droppedPin(point))
            true
        }
        // Long-press works the same, but sets the trip's *start* point.
        m.addOnMapLongClickListener { point ->
            val lm = placeAtTap(m, point)?.toLandmark() ?: droppedPin(point)
            origin = lm
            setOriginMarker(lm)
            toast("Start point: ${lm.name}")
            true
        }
        // During navigation, a user drag/zoom releases the auto-follow (Google-style).
        m.addOnCameraMoveStartedListener { reason ->
            if (navigating && followingUser &&
                reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            ) {
                followingUser = false
                binding.recenterBtn.visibility = View.VISIBLE
            }
        }
    }

    // ---- Search ----------------------------------------------------------

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                if (suppressWatcher) return
                val q = s?.toString().orEmpty()
                binding.clearBtn.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                renderResults(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            val q = binding.searchInput.text.toString()
            matches(q).firstOrNull()?.let { lm -> selectDestination(lm) }
            true
        }
        binding.clearBtn.setOnClickListener {
            binding.searchInput.setText("")
            binding.clearBtn.visibility = View.GONE
            renderResults("")   // fall back to popular list
        }
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.searchInput.text.isNullOrBlank()) renderResults("")
        }
    }

    private fun matches(query: String): List<Landmark> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return BEIRUT_LANDMARKS.filter {
            it.name.contains(q, ignoreCase = true) || it.kind.contains(q, ignoreCase = true)
        }.take(8)
    }

    private val popularNames = listOf(
        "ABC Mall Achrafieh", "Beirut Souks", "Raouché / Pigeon Rocks",
        "Sassine Square", "Hamra Street", "Zaitunay Bay",
    )

    private fun renderResults(query: String) {
        val blank = query.isBlank()
        val list = if (blank)
            popularNames.mapNotNull { n -> BEIRUT_LANDMARKS.find { it.name == n } }
        else matches(query)

        val container = binding.resultsContainer
        container.removeAllViews()
        if (list.isEmpty()) {
            binding.resultsCard.visibility = View.GONE
            binding.locateFab.visibility = View.VISIBLE
            return
        }
        if (blank) container.addView(sectionHeader("POPULAR"))
        list.forEachIndexed { i, lm ->
            if (i > 0) container.addView(rowDivider())
            container.addView(resultRow(lm))
        }
        binding.resultsCard.visibility = View.VISIBLE
        binding.locateFab.visibility = View.GONE   // don't poke through the results card
    }

    private fun resultRow(lm: Landmark): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            background = themedRipple()
        }
        val cat = allCats.first { it.key == catKeyFor(lm.kind) }
        val icon = ImageView(this).apply {
            setImageBitmap(makeMarker(cat.color, cat.glyph))
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = lm.name
            setTextColor(Color.parseColor("#202124"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = kindLabel(lm.kind)
            setTextColor(Color.parseColor("#5F6368"))
            textSize = 13f
        })
        row.addView(icon)
        row.addView(texts)
        row.setOnClickListener { selectDestination(lm) }
        return row
    }

    private fun sectionHeader(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#5F6368"))
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
        setPadding(dp(16), dp(12), dp(16), dp(4))
    }

    private fun rowDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(62)
        }
        setBackgroundColor(Color.parseColor("#EDEEF0"))
    }

    internal fun themedRipple(): android.graphics.drawable.Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = a.getDrawable(0)
        a.recycle()
        return d
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "mall" -> "Shopping"
        "university" -> "University"
        "hospital" -> "Hospital"
        "mosque" -> "Mosque"
        "church" -> "Church"
        "seaside" -> "Seaside"
        "square" -> "Square"
        "museum" -> "Museum"
        "bank" -> "Bank"
        "street" -> "Street"
        "station" -> "Station"
        "junction" -> "Junction"
        else -> kind.replaceFirstChar { it.uppercase() }
    }

    /** Pick a destination from search: sync the field, dismiss keyboard/results, route. */
    private fun selectDestination(lm: Landmark) {
        suppressWatcher = true
        binding.searchInput.setText(lm.name)
        binding.searchInput.setSelection(lm.name.length)
        suppressWatcher = false
        binding.clearBtn.visibility = View.VISIBLE
        binding.resultsCard.visibility = View.GONE
        binding.locateFab.visibility = View.VISIBLE
        hideKeyboard()
        routeTo(lm)
    }

    // ---- Explore (places directory) --------------------------------------

    private val placesAdapter by lazy { PlacesAdapter { placeDetail.show(it) } }

    private fun setupExplore() {
        binding.placesList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.placesList.adapter = placesAdapter
        binding.placesList.addItemDecoration(
            com.google.android.material.divider.MaterialDividerItemDecoration(
                this, com.google.android.material.divider.MaterialDividerItemDecoration.VERTICAL
            ).apply {
                dividerColor = androidx.core.content.ContextCompat.getColor(this@MainActivity, R.color.hairline)
                dividerThickness = dp(1)
                dividerInsetStart = dp(92)
                isLastItemDecorated = false
            }
        )
        renderChips()
        renderPlaces()
        binding.exploreSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                binding.exploreClear.visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                renderPlaces()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.exploreClear.setOnClickListener {
            binding.exploreSearch.setText("")
            hideKeyboard()
            renderPlaces()
        }
    }

    private fun hideMapOverlays() {
        binding.listFab.visibility = View.GONE
        binding.locateFab.visibility = View.GONE
    }

    private fun showExplore() {
        binding.exploreScreen.visibility = View.VISIBLE
        binding.contributeScreen.visibility = View.GONE
        binding.profileScreen.visibility = View.GONE
        binding.bottomNav.visibility = View.VISIBLE
        hideMapOverlays()
    }

    private fun showContribute() {
        binding.exploreScreen.visibility = View.GONE
        binding.contributeScreen.visibility = View.VISIBLE
        binding.profileScreen.visibility = View.GONE
        binding.bottomNav.visibility = View.VISIBLE
        hideMapOverlays()
    }

    private fun showProfile() {
        binding.exploreScreen.visibility = View.GONE
        binding.contributeScreen.visibility = View.GONE
        binding.profileScreen.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.VISIBLE
        hideMapOverlays()
    }

    /** The map is a sub-screen of Explore — full-screen, no bottom nav. */
    internal fun showMap() {
        binding.exploreScreen.visibility = View.GONE
        binding.contributeScreen.visibility = View.GONE
        binding.profileScreen.visibility = View.GONE
        binding.bottomNav.visibility = View.GONE
        binding.listFab.visibility = View.VISIBLE
        binding.locateFab.visibility = View.VISIBLE
    }

    // ---- Contribute + Profile (MVP stubs) --------------------------------

    private fun setupContribute() {
        val rows = listOf(
            Triple(R.drawable.ic_tab_contribute, "Add a place", "A shop, café, clinic, gas station…"),
            Triple(R.drawable.ic_star, "Write a review", "Rate places you've visited"),
            Triple(R.drawable.ic_list, "Suggest an edit", "Fix hours, location or details"),
            Triple(R.drawable.ic_cat_shopping, "Add photos", "Upload photos of a place"),
            Triple(R.drawable.ic_cat_atm, "Claim your business", "Manage your listing & promote"),
        )
        binding.contributeList.removeAllViews()
        rows.forEachIndexed { i, (ic, t, s) ->
            if (i > 0) binding.contributeList.addView(placeDivider())
            binding.contributeList.addView(infoRow(ic, t, s))
        }
    }

    private fun setupProfile() {
        binding.signInBtn.setOnClickListener { toast("Sign-in — coming soon") }
        val rows = listOf(
            Triple(R.drawable.ic_list, "Saved places", "Your bookmarks"),
            Triple(R.drawable.ic_star, "My reviews", "Reviews you've written"),
            Triple(R.drawable.ic_cat_shopping, "My photos", "Photos you've added"),
            Triple(R.drawable.ic_cat_atm, "Business tools", "Claim & promote your place"),
            Triple(R.drawable.ic_tab_explore, "Settings", "App preferences"),
        )
        binding.profileList.removeAllViews()
        rows.forEachIndexed { i, (ic, t, s) ->
            if (i > 0) binding.profileList.addView(placeDivider())
            binding.profileList.addView(infoRow(ic, t, s))
        }
        // OSM attribution (kept off the map, shown here for license compliance).
        binding.profileList.addView(TextView(this).apply {
            text = "Map data © OpenStreetMap contributors · Tiles by OpenFreeMap"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(dp(20), dp(28), dp(20), dp(8))
        })
    }

    private fun infoRow(icon: Int, title: String, subtitle: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            background = themedRipple()
        }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3C4043"))
            background = AppCompatResources.getDrawable(context, R.drawable.thumb_bg)
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.parseColor("#202124"))
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = subtitle
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 13f
                setPadding(0, dp(1), 0, 0)
            })
        })
        row.setOnClickListener { toast("$title — coming soon") }
        return row
    }

    private fun renderChips() {
        val row = binding.chipsRow
        row.removeAllViews()
        PLACE_CHIPS.forEachIndexed { i, chip ->
            row.addView(chipView(chip, i))
        }
    }

    private fun chipView(chip: PlaceChip, index: Int): View {
        val on = index == currentChip
        val tv = TextView(this).apply {
            text = chip.label
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(16), dp(9))
            setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#202124"))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(Color.parseColor(if (on) "#202124" else "#F1F3F4"))
            }
            background = bg
            if (chip.glyph != null) {
                val d = AppCompatResources.getDrawable(this@MainActivity, chip.glyph)!!.mutate()
                d.setTint(Color.parseColor(if (on) "#FFFFFF" else "#5F6368"))
                d.setBounds(0, 0, dp(17), dp(17))
                setCompoundDrawablesRelative(d, null, null, null)
                compoundDrawablePadding = dp(6)
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
        tv.layoutParams = lp
        tv.setOnClickListener {
            currentChip = index
            renderChips()
            renderPlaces()
        }
        return tv
    }

    private fun renderPlaces() {
        val chip = PLACE_CHIPS[currentChip]
        val q = binding.exploreSearch.text?.toString()?.trim().orEmpty()
        var list = PLACES
        if (chip.cats != null) list = list.filter { it.category in chip.cats }
        if (q.isNotEmpty()) list = list.filter {
            it.name.contains(q, true) || it.category.label.contains(q, true) || it.area.contains(q, true)
        }
        list = list.sortedWith(compareByDescending<Place> { it.promoted }.thenByDescending { it.rating })

        binding.resultsCount.text =
            if (list.size == 1) "1 place" else "${list.size} places"
        placesAdapter.submitList(list)
    }

    private fun placeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(92)
        }
        setBackgroundColor(Color.parseColor("#EDEEF0"))
    }

    /** Dismiss the sheet entirely (X button): clear the selection, route and markers. */
    private fun closeSheet() {
        currentDest = null
        taxi.reset()
        sheet.isHideable = true
        sheet.state = BottomSheetBehavior.STATE_HIDDEN
        geoSource(SRC_ROUTE)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        geoSource(SRC_DEST)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    // ---- Routing ---------------------------------------------------------

    internal fun routeTo(dest: Landmark) {
        if (dest.name == origin.name) {
            toast("That's already your start point — long-press another pin to change it.")
            return
        }
        currentDest = dest
        setDestMarker(dest)
        binding.routeTitle.text = "${origin.name}  →  ${dest.name}"
        // A place is selected — keep the sheet present (collapsible, but not swipe-to-dismiss).
        sheet.isHideable = false
        if (sheet.state == BottomSheetBehavior.STATE_HIDDEN) {
            sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        updateModeButtons()
        requestRoute()
    }

    private fun setMode(mode: TravelMode) {
        currentMode = mode
        updateModeButtons()
        if (currentDest != null) requestRoute()
    }

    private fun requestRoute() {
        val dest = currentDest ?: return
        val mode = currentMode
        val o = origin
        binding.routeMeta.text = "Finding the way…"
        binding.stepsContainer.removeAllViews()
        binding.startNavBtn.visibility = View.GONE
        taxi.reset()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRoute(o, dest, mode) }
            if (result == null || result.geometry.size < 2) {
                binding.routeMeta.text = "Couldn't find a $mode route. Check your connection."
                return@launch
            }
            currentRoute = result
            drawRoute(result.geometry)
            fitTo(o.latLng, dest.latLng)
            val km = result.distanceMeters / 1000.0
            val min = (result.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
            binding.routeMeta.text = "%.1f km · %d min %s".format(km, min, mode.suffix)

            if (mode == TravelMode.TAXI) {
                // Uber-style: show ride options + fares instead of turn-by-turn.
                binding.stepsHeader.visibility = View.GONE
                binding.stepsContainer.visibility = View.GONE
                binding.startNavBtn.visibility = View.GONE
                binding.taxiPanel.visibility = View.VISIBLE
                taxi.render(dest, result)
                sheet.state = BottomSheetBehavior.STATE_EXPANDED
            } else {
                binding.taxiPanel.visibility = View.GONE
                binding.stepsHeader.visibility = View.VISIBLE
                binding.stepsContainer.visibility = View.VISIBLE
                renderSteps(dest, result.steps)
                binding.startNavBtn.visibility = View.VISIBLE
            }
        }
    }

    private fun updateModeButtons() {
        listOf(
            binding.btnDrive to TravelMode.DRIVE,
            binding.btnWalk to TravelMode.WALK,
            binding.btnTaxi to TravelMode.TAXI,
        ).forEach { (btn, m) ->
            val on = m == currentMode
            val bg = Color.parseColor(if (on) "#202124" else "#F1F3F4")
            val fg = Color.parseColor(if (on) "#FFFFFF" else "#202124")
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
            btn.setTextColor(fg)
            btn.iconTint = android.content.res.ColorStateList.valueOf(fg)
        }
    }

    // ---- Navigation mode -------------------------------------------------

    @android.annotation.SuppressLint("MissingPermission")
    private fun startNavigation() {
        val dest = currentDest ?: return

        // Real navigation needs the device's position from the GPS sensor.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            locationPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            toast("Allow location, then tap Start to begin navigation.")
            return
        }

        // You navigate from where you ACTUALLY are — so route from the live fix, not from
        // whatever origin was previewed. This is what keeps the puck, steps and arrival honest.
        val lmgr = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
        ).filter { lmgr.isProviderEnabled(it) }
        val here = providers.mapNotNull { lmgr.getLastKnownLocation(it) }.minByOrNull { it.accuracy }
        if (here == null) {
            toast("Waiting for a GPS fix… tap the locate button, then Start.")
            requestLocate(silent = true)
            return
        }
        origin = Landmark("Your location", "you", here.latitude, here.longitude)
        setOriginMarker(origin)

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

        val mode = currentMode
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRoute(origin, dest, mode) }
            if (!navigating) return@launch            // user pressed End while we fetched
            if (result == null || result.geometry.size < 2) {
                toast("Couldn't get a route from your location. Check your connection.")
                endNavigation()
                return@launch
            }
            currentRoute = result
            navPath = result.geometry
            navCum = cumulative(navPath)
            navTotal = navCum.last()
            buildNavSteps(result, dest)
            drawRoute(navPath)
            navTrack.clear()
            navLogStartMs = System.currentTimeMillis()
            navLogged = false
            speak("Starting ${mode.label.lowercase(java.util.Locale.US)} to ${dest.name}.")
            startLocationTracking(dest)
            // Go foreground so fixes keep arriving with the app backgrounded / screen locked.
            NavService.start(this@MainActivity, "Navigating to ${dest.name}", "Starting…")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startLocationTracking(dest: Landmark) {
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
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
        for (p in providers) lm.requestLocationUpdates(p, 1000L, 0f, listener, mainLooper)
        // Seed immediately with the best last-known fix so the camera flies in at once.
        providers.mapNotNull { lm.getLastKnownLocation(it) }
            .minByOrNull { it.accuracy }
            ?.let { onNavFix(it, dest) }
    }

    private fun stopLocationTracking() {
        navListener?.let {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
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
                map?.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(aim).zoom(currentMode.camZoom).tilt(58.0).bearing(navCamBrg).build()
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
                .target(aim).zoom(currentMode.camZoom).tilt(58.0).bearing(navCamBrg).build()
            // Animate between fixes (~1 s apart) so movement glides instead of jumping.
            map?.animateCamera(CameraUpdateFactory.newCameraPosition(cam), 950)
        }

        updateNavBanner(dist, navTotal, currentMode)
        maybeAnnounce(dist, currentMode)

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
        val mode = currentMode
        val here = Landmark("Your location", "you", from.latitude, from.longitude)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { fetchRoute(here, dest, mode) }
            rerouting = false
            if (!navigating) return@launch
            if (result == null || result.geometry.size < 2) return@launch  // keep old route; retry later
            origin = here
            setOriginMarker(here)
            currentRoute = result
            navPath = result.geometry
            navCum = cumulative(navPath)
            navTotal = navCum.last()
            buildNavSteps(result, dest)
            drawRoute(navPath)
            navProgress = 0.0
            navPtr = 0
            announcedPtr = -1
            offRouteFixes = 0
            camPrimed = false        // re-lock the camera heading on the next fix
        }
    }

    private fun endNavigation() {
        // Save whatever we have (suggestion + partial track) before tearing down.
        val d = currentDest; val r = currentRoute
        if (!navLogged && d != null && r != null && navPath.size >= 2) saveRouteLog(d, r, completed = false)
        stopLocationTracking()
        NavService.stop(this)
        navigating = false
        binding.recenterBtn.visibility = View.GONE
        tts?.stop()
        clearPuck()
        enterNavUi(false)
        currentDest?.let { fitTo(origin.latLng, it.latLng) }  // resets tilt/bearing to north-up
    }

    private fun onArrived(dest: Landmark) {
        navigating = false
        NavService.stop(this)
        binding.recenterBtn.visibility = View.GONE
        binding.navArrow.text = "●"
        binding.navDist.text = "Arrived"
        binding.navTurn.text = "You've reached ${arrivalName(dest)}"
        binding.navRemaining.text = "0 m left"
        speak("You have arrived at ${arrivalName(dest)}.")
        currentRoute?.let { if (!navLogged) saveRouteLog(dest, it, completed = true) }
        toast("Arrived at ${dest.name}")
    }

    /**
     * Persist the navigation session to a JSON file on the device: the OSRM suggestion
     * (geometry, distance/duration, raw maneuvers, our landmark itinerary) plus the real
     * GPS track. Pull with adb afterwards to analyse whether the suggestions look good.
     */
    private fun saveRouteLog(dest: Landmark, result: RouteResult, completed: Boolean) {
        try {
            val dir = java.io.File(filesDir, "routes").apply { mkdirs() }
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
                put("mode", currentMode.label)
                put("origin", org.json.JSONObject().put("name", origin.name).put("lat", origin.lat).put("lng", origin.lng))
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
    private fun recenter() {
        binding.recenterBtn.visibility = View.GONE
        camPrimed = false          // let the next GPS fix re-lock heading to the puck
        val pos = lastPuck
        if (pos == null) { followingUser = true; return }
        val cam = CameraPosition.Builder()
            .target(destinationPoint(pos.latitude, pos.longitude, lastBrg, 45.0))
            .zoom(currentMode.camZoom).tilt(58.0).bearing(lastBrg).build()
        map?.animateCamera(
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
            val near = nearestLandmark(LatLng(s.lat, s.lng), setOf(origin.name, dest.name), 450.0)
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
            sheet.isHideable = true
            sheet.state = BottomSheetBehavior.STATE_HIDDEN
        } else {
            sheet.isHideable = false
            sheet.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        binding.locateFab.visibility = if (on) View.GONE else View.VISIBLE
        binding.listFab.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun setPuckAt(pos: LatLng, bearing: Double) {
        val f = Feature.fromGeometry(Point.fromLngLat(pos.longitude, pos.latitude))
        f.addNumberProperty("bearing", bearing)
        geoSource(SRC_PUCK)?.setGeoJson(f)
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
        geoSource(SRC_PUCK)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun followCamera(pos: LatLng, bearing: Double, zoom: Double, tilt: Double) {
        val cam = CameraPosition.Builder()
            .target(pos).zoom(zoom).tilt(tilt).bearing(bearing).build()
        map?.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    /**
     * Build a clean *landmark-only* itinerary — the way people here actually give
     * directions: only the well-known places, not every micro-turn or street name.
     */
    private fun renderSteps(dest: Landmark, steps: List<Maneuver>) {
        val container = binding.stepsContainer
        container.removeAllViews()
        buildItinerary(dest, steps).forEachIndexed { i, phrase ->
            container.addView(stepRow(i + 1, phrase))
        }
    }

    /** The clean landmark-only itinerary (also written to the saved route log for analysis). */
    private fun buildItinerary(dest: Landmark, steps: List<Maneuver>): List<String> {
        val phrases = ArrayList<String>()
        phrases.add("Start at ${origin.name}.")
        var lastLandmark = origin.name
        steps.forEachIndexed { i, step ->
            if (i == 0 || step.type == "arrive") return@forEachIndexed
            val near = nearestLandmark(LatLng(step.lat, step.lng), setOf(origin.name, dest.name), 450.0)
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
        val tv = TextView(this)
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

    // ---- Map data updates ------------------------------------------------

    /**
     * A GeoJSON source, but only from a style that is actually current and fully loaded.
     * The map reloads its style when the GL surface is recreated (screen off/on, etc.);
     * touching a source on the old/loading style throws IllegalStateException. Reading the
     * live style each time and gating on isFullyLoaded makes every map update crash-safe.
     */
    private fun geoSource(id: String): GeoJsonSource? {
        val s = map?.style?.takeIf { it.isFullyLoaded } ?: return null
        return s.getSourceAs(id)
    }

    private fun setOriginMarker(lm: Landmark) {
        geoSource(SRC_ORIGIN)?.setGeoJson(Point.fromLngLat(lm.lng, lm.lat))
    }

    private fun setDestMarker(lm: Landmark) {
        geoSource(SRC_DEST)?.setGeoJson(Point.fromLngLat(lm.lng, lm.lat))
    }

    private fun drawRoute(points: List<Point>) {
        geoSource(SRC_ROUTE)?.setGeoJson(LineString.fromLngLats(points))
    }

    private fun fitTo(a: LatLng, b: LatLng) {
        val bounds = LatLngBounds.Builder().include(a).include(b).build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(70)))
    }

    // ---- Geo helpers -----------------------------------------------------

    /** If the tap actually landed on a place pin (or its label), return that place. */
    private fun placeAtTap(m: MapLibreMap, point: LatLng): Place? {
        val screen = m.projection.toScreenLocation(point)
        val hit = m.queryRenderedFeatures(screen, "place-pins", "place-labels")
        val name = hit.firstOrNull { it.hasProperty("name") }?.getStringProperty("name") ?: return null
        return PLACES.firstOrNull { it.name == name }
    }

    /** A directory place as a routing destination/origin (kept distinct from a dropped point). */
    private fun Place.toLandmark(): Landmark = Landmark(name, "place", lat, lng)

    /** An ad-hoc destination/origin at an arbitrary map point, phrased in landmark terms. */
    private fun droppedPin(point: LatLng): Landmark {
        val near = nearestLandmark(point, maxMeters = 250.0)
        val name = if (near != null) "Near ${near.name}" else "Dropped pin"
        return Landmark(name, "point", point.latitude, point.longitude)
    }

    private fun nearestLandmark(p: LatLng, exclude: Set<String> = emptySet(), maxMeters: Double): Landmark? {
        var best: Landmark? = null
        var bestDist = Double.MAX_VALUE
        for (lm in BEIRUT_LANDMARKS) {
            if (lm.name in exclude) continue
            val d = haversine(p.latitude, p.longitude, lm.lat, lm.lng)
            if (d < bestDist) {
                bestDist = d
                best = lm
            }
        }
        return if (bestDist <= maxMeters) best else null
    }

    private fun drawableToBitmap(d: android.graphics.drawable.Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bmp
    }

    /** A round category marker: coloured circle with a white ring + white glyph (or centre dot). */
    private fun makeMarker(colorHex: String, glyphRes: Int?): Bitmap {
        val size = dp(32)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, size / 2f - dp(2), p)          // white ring
        p.color = Color.parseColor(colorHex)
        c.drawCircle(cx, cy, size / 2f - dp(4), p)          // coloured fill
        if (glyphRes != null) {
            val d = AppCompatResources.getDrawable(this, glyphRes)!!.mutate()
            d.setTint(Color.WHITE)
            val g = (size * 0.52f).toInt()
            val off = (size - g) / 2
            d.setBounds(off, off, off + g, off + g)
            d.draw(c)
        } else {
            p.color = Color.WHITE
            c.drawCircle(cx, cy, size * 0.16f, p)           // plain centre dot
        }
        return bmp
    }

    // ---- Small UI utils --------------------------------------------------

    /**
     * targetSdk 35 draws the app edge-to-edge (behind the status/nav bars). Keep the
     * map full-bleed, but inset the floating UI so the search bar clears the status bar
     * and the bottom cards clear the navigation bar.
     */
    private fun applyEdgeInsets() {
        androidx.core.view.WindowCompat.getInsetsController(window, binding.root)
            .isAppearanceLightStatusBars = true
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            (binding.searchCard.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .topMargin = bars.top + dp(4)
            (binding.locateFab.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .topMargin = bars.top + dp(12)
            (binding.navBanner.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .topMargin = bars.top + dp(8)
            (binding.navBar.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .bottomMargin = bars.bottom + dp(8)
            (binding.listFab.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .topMargin = bars.top + dp(12)
            (binding.recenterBtn.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .bottomMargin = bars.bottom + dp(96)
            binding.sheetContent.setPadding(dp(18), dp(10), dp(18), dp(18) + bars.bottom)
            binding.exploreHeader.setPadding(dp(20), bars.top + dp(12), dp(20), dp(6))
            binding.contributeHeader.setPadding(dp(20), bars.top + dp(12), dp(20), dp(4))
            binding.profileHeader.setPadding(dp(20), bars.top + dp(12), dp(20), dp(10))
            binding.bottomNav.setPadding(0, 0, 0, bars.bottom)
            binding.detailTopBar.setPadding(dp(6), bars.top, dp(6), 0)
            binding.searchCard.requestLayout()
            binding.locateFab.requestLayout()
            binding.navBanner.requestLayout()
            binding.navBar.requestLayout()
            binding.listFab.requestLayout()
            insets
        }
    }

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    internal fun toast(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    // ---- MapView lifecycle ----------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (placeDetail.isVisible()) {
            placeDetail.hide()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onResume() {
        super.onResume(); binding.mapView.onResume()
        // Coming back from a screen lock: if navigation is live but the OS stopped
        // delivering fixes while we were away, re-attach so the puck keeps moving.
        if (navigating && navListener == null) currentDest?.let { startLocationTracking(it) }
    }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() {
        // Don't kill navigation when the screen locks — keep GPS + guidance running while
        // navigating; only drop the transient locate listener when we're not navigating.
        if (!navigating) stopLocationTracking()
        taxi.cancelPending(); binding.mapView.onStop(); super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        runCatching { unregisterReceiver(endNavReceiver) }
        binding.mapView.onDestroy(); super.onDestroy()
    }

    // ---- Foreground navigation notification ------------------------------

    private fun ensureNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
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
        NavService.start(this, title, text)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
