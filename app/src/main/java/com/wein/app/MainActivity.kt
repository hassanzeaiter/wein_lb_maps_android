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
    internal var map: MapLibreMap? = null
    private var style: Style? = null

    // Directory shown as map pins. Starts as the bundled seed, refreshed from the backend.
    private var mapPlaces: List<Place> = PLACES

    // Default trip start; changed by long-pressing a pin or using current location.
    internal var origin: Landmark = BEIRUT_LANDMARKS.first { it.name == "AUB Main Gate" }
    private var suppressWatcher = false

    // Shared trip state — read/written by routing here and by NavigationController.
    internal var currentMode = TravelMode.DRIVE
    internal var currentDest: Landmark? = null
    internal var currentRoute: RouteResult? = null

    // In-app taxi (ride-hailing) flow — self-contained; see TaxiController.
    private val taxi by lazy { TaxiController(this, binding) }

    // Place-detail screen — self-contained; see PlaceDetailController.
    private val placeDetail by lazy { PlaceDetailController(this, binding) }

    // Explore home (chips + search + place list) — self-contained; see ExploreController.
    private val explore by lazy { ExploreController(this, binding) { placeDetail.show(it) } }

    // Sign in / sign up + Profile header — self-contained; see AuthController.
    private val auth by lazy { AuthController(this, binding) }

    // Profile menu + Saved/Reviews/Photos sub-screens — self-contained; see ProfileController.
    private val profile by lazy { ProfileController(this, binding) { placeDetail.show(it) } }

    internal lateinit var sheet: BottomSheetBehavior<*>
    private var didAutoLocate = false

    // Live GPS navigation (puck, chase-cam, reroute, voice, logging) — see NavigationController.
    private val nav by lazy { NavigationController(this, binding) }

    internal val locationPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) useCurrentLocation()
            else toast("Location permission is needed to use your position.")
        }

    // Notifications are optional — the foreground service runs either way; without the
    // permission the ongoing nav notification just isn't shown.
    internal val notifPermLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    // The "End" action on the nav notification broadcasts here so we tear the session down
    // through the same path as the in-app End button.
    private val endNavReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (nav.isNavigating()) nav.end()
        }
    }

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/positron"
        private val BEIRUT = LatLng(33.8925, 35.5040)

        private const val SRC_PLACES = "places"
        private const val SRC_ROUTE = "route"
        private const val SRC_ORIGIN = "origin"
        private const val SRC_DEST = "dest"
        internal const val SRC_PUCK = "puck"    // read by NavigationController's puck layer
        private const val PUCK_IMG = "puck-arrow"

        private const val ROUTE_COLOR = "#202124"
        private const val ROUTE_CASING = "#FFFFFF"
        private const val ORIGIN_COLOR = "#FFFFFF"   // white "start" dot (black ring)
        private const val DEST_COLOR = "#202124"     // black destination dot
        private const val PIN_COLOR = "#3C4043"      // one dark-gray for all pins
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
        Session.load(this)
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
                loadMapPlaces()
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
        binding.startNavBtn.setOnClickListener { nav.start() }
        binding.endNavBtn.setOnClickListener { nav.end() }
        binding.muteBtn.setOnClickListener { nav.toggleVoice() }

        // Explore (places directory) is the home; the map is the "Directions" tool.
        binding.topContainer.visibility = View.GONE   // Explore's search replaces the map search
        binding.mapFab.setOnClickListener { showMap() }
        binding.listFab.setOnClickListener { showExplore() }
        binding.closeSheetBtn.setOnClickListener { closeSheet() }
        binding.recenterBtn.setOnClickListener { nav.recenter() }
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
        binding.writeReviewBtn.setOnClickListener {
            if (Session.isLoggedIn) placeDetail.promptWriteReview()
            else { toast("Sign in to write a review"); auth.onSignInClicked() }
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.tab_explore -> showExplore()
                R.id.tab_contribute -> showContribute()
                R.id.tab_profile -> showProfile()
            }
            true
        }
        explore.setup()
        setupContribute()
        setupProfile()
        showExplore()

        androidx.core.content.ContextCompat.registerReceiver(
            this, endNavReceiver,
            android.content.IntentFilter(NavService.ACTION_END_NAV),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    // ---- Current location ------------------------------------------------

    internal fun requestLocate(silent: Boolean = false) {
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

        // Our directory places, as tappable pins. Each feature carries its category so the map
        // picks the right glyph (these are the real listings, not the landmarks — landmarks stay
        // behind the scenes as the vocabulary for turn directions). Starts as the bundled seed;
        // loadMapPlaces() swaps in the backend directory once the style is ready.
        style.addSource(GeoJsonSource(SRC_PLACES, placeFeatures(mapPlaces)))
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
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                nav.onCameraMovedByGesture()
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

    private fun hideMapOverlays() {
        binding.listFab.visibility = View.GONE
        binding.locateFab.visibility = View.GONE
    }

    private fun showExplore() {
        binding.exploreScreen.visibility = View.VISIBLE
        binding.contributeScreen.visibility = View.GONE
        binding.profileScreen.visibility = View.GONE
        profile.hide()
        binding.bottomNav.visibility = View.VISIBLE
        hideMapOverlays()
    }

    private fun showContribute() {
        binding.exploreScreen.visibility = View.GONE
        binding.contributeScreen.visibility = View.VISIBLE
        binding.profileScreen.visibility = View.GONE
        profile.hide()
        binding.bottomNav.visibility = View.VISIBLE
        hideMapOverlays()
    }

    private fun showProfile() {
        binding.exploreScreen.visibility = View.GONE
        binding.contributeScreen.visibility = View.GONE
        binding.profileScreen.visibility = View.VISIBLE
        profile.hide()
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
        binding.signInBtn.setOnClickListener { auth.onSignInClicked() }
        auth.refreshProfileHeader()
        profile.setup()
        binding.profileSubBack.setOnClickListener { profile.hide() }
    }

    /** Open the sign-in dialog (used by actions that require an account, e.g. Save). */
    internal fun promptSignIn() = auth.onSignInClicked()

    internal fun infoRow(
        icon: Int,
        title: String,
        subtitle: String,
        onClick: () -> Unit = { toast("$title — coming soon") },
    ): View {
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
        row.setOnClickListener { onClick() }
        return row
    }

    internal fun placeDivider(): View = View(this).apply {
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
                nav.renderSteps(dest, result.steps)
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

    // ---- Map data updates ------------------------------------------------

    /**
     * A GeoJSON source, but only from a style that is actually current and fully loaded.
     * The map reloads its style when the GL surface is recreated (screen off/on, etc.);
     * touching a source on the old/loading style throws IllegalStateException. Reading the
     * live style each time and gating on isFullyLoaded makes every map update crash-safe.
     */
    internal fun geoSource(id: String): GeoJsonSource? {
        val s = map?.style?.takeIf { it.isFullyLoaded } ?: return null
        return s.getSourceAs(id)
    }

    internal fun setOriginMarker(lm: Landmark) {
        geoSource(SRC_ORIGIN)?.setGeoJson(Point.fromLngLat(lm.lng, lm.lat))
    }

    private fun setDestMarker(lm: Landmark) {
        geoSource(SRC_DEST)?.setGeoJson(Point.fromLngLat(lm.lng, lm.lat))
    }

    internal fun drawRoute(points: List<Point>) {
        geoSource(SRC_ROUTE)?.setGeoJson(LineString.fromLngLats(points))
    }

    internal fun fitTo(a: LatLng, b: LatLng) {
        val bounds = LatLngBounds.Builder().include(a).include(b).build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, dp(70)))
    }

    // ---- Geo helpers -----------------------------------------------------

    /** If the tap actually landed on a place pin (or its label), return that place. */
    private fun placeAtTap(m: MapLibreMap, point: LatLng): Place? {
        val screen = m.projection.toScreenLocation(point)
        val hit = m.queryRenderedFeatures(screen, "place-pins", "place-labels")
        val name = hit.firstOrNull { it.hasProperty("name") }?.getStringProperty("name") ?: return null
        return mapPlaces.firstOrNull { it.name == name }
    }

    /** Build the pin GeoJSON for a set of places (one feature per place, with its category glyph). */
    private fun placeFeatures(places: List<Place>): FeatureCollection = FeatureCollection.fromFeatures(
        places.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
                addStringProperty("name", p.name)
                addStringProperty("icon", "place-${p.category.name}")
            }
        }
    )

    /** Load the directory from the backend and refresh the map pins (keeps the seed on failure). */
    private fun loadMapPlaces() {
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) { runCatching { PlacesApi.fetchPlaces() }.getOrNull() }
            if (!fetched.isNullOrEmpty()) {
                mapPlaces = fetched
                geoSource(SRC_PLACES)?.setGeoJson(placeFeatures(fetched))
            }
        }
    }

    /** A directory place as a routing destination/origin (kept distinct from a dropped point). */
    private fun Place.toLandmark(): Landmark = Landmark(name, "place", lat, lng)

    /** An ad-hoc destination/origin at an arbitrary map point, phrased in landmark terms. */
    private fun droppedPin(point: LatLng): Landmark {
        val near = nearestLandmark(point, maxMeters = 250.0)
        val name = if (near != null) "Near ${near.name}" else "Dropped pin"
        return Landmark(name, "point", point.latitude, point.longitude)
    }

    internal fun nearestLandmark(p: LatLng, exclude: Set<String> = emptySet(), maxMeters: Double): Landmark? {
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
            binding.profileSubBar.setPadding(dp(6), bars.top, dp(6), 0)
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

    internal fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    // ---- MapView lifecycle ----------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (placeDetail.isVisible()) {
            placeDetail.hide()
        } else if (profile.isVisible()) {
            profile.hide()
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
        nav.onResume()
    }
    override fun onPause() { binding.mapView.onPause(); super.onPause() }
    override fun onStop() {
        // Don't kill navigation when the screen locks — keep GPS + guidance running while
        // navigating; only drop the transient locate listener when we're not navigating.
        nav.onStop()
        taxi.cancelPending(); binding.mapView.onStop(); super.onStop()
    }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        nav.dispose()
        runCatching { unregisterReceiver(endNavReceiver) }
        binding.mapView.onDestroy(); super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
