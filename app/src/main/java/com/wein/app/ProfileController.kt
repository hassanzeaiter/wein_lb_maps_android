package com.wein.app

import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import coil.load
import com.wein.app.databinding.ActivityMainBinding
import com.wein.app.databinding.ItemPlaceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Profile tab: the menu rows plus the Saved places / My reviews / My photos sub-screens
 * (COD-255). The menu lives in `profileList`; each of the three rows opens the shared
 * `profileSubScreen` overlay, which loads the signed-in user's data from the backend.
 *
 * The account header + sign-in button stay with [AuthController]; screen routing (tab
 * switching, back handling) stays in [MainActivity], which forwards Back here via [hide].
 */
internal class ProfileController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val onOpenPlace: (Place) -> Unit,
) {
    /** Which list the sub-screen is currently showing. */
    private enum class Section(val title: String) {
        SAVED("Saved places"),
        REVIEWS("My reviews"),
        PHOTOS("My photos"),
    }

    private var current: Section? = null

    private fun dp(v: Int) = activity.dp(v)

    private companion object {
        const val LOAD_ERROR = "Couldn't load this.\nCheck your connection and try again."
    }

    /** Build the Profile menu rows. Called once from MainActivity.setupProfile(). */
    fun setup() {
        val rows = listOf(
            Row(R.drawable.ic_bookmark, "Saved places", "Your bookmarks") { open(Section.SAVED) },
            Row(R.drawable.ic_star, "My reviews", "Reviews you've written") { open(Section.REVIEWS) },
            Row(R.drawable.ic_cat_shopping, "My photos", "Photos you've added") { open(Section.PHOTOS) },
            Row(R.drawable.ic_cat_atm, "Business tools", "Claim & promote your place") {
                activity.toast("Business tools — coming soon")
            },
            Row(R.drawable.ic_tab_explore, "Settings", "App preferences") {
                activity.toast("Settings — coming soon")
            },
        )
        binding.profileList.removeAllViews()
        rows.forEachIndexed { i, r ->
            if (i > 0) binding.profileList.addView(activity.placeDivider())
            binding.profileList.addView(activity.infoRow(r.icon, r.title, r.subtitle, r.onClick))
        }
        // OSM attribution (kept off the map, shown here for license compliance).
        binding.profileList.addView(TextView(activity).apply {
            text = "Map data © OpenStreetMap contributors · Tiles by OpenFreeMap"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 12f
            setPadding(dp(20), dp(28), dp(20), dp(8))
        })
    }

    private data class Row(val icon: Int, val title: String, val subtitle: String, val onClick: () -> Unit)

    fun isVisible(): Boolean = binding.profileSubScreen.visibility == View.VISIBLE

    fun hide() {
        binding.profileSubScreen.visibility = View.GONE
        binding.bottomNav.visibility = View.VISIBLE
        current = null
    }

    /** Open a sub-screen and load its data. Signed-out users see a prompt to sign in. */
    private fun open(section: Section) {
        current = section
        binding.profileSubTitle.text = section.title
        binding.profileSubList.removeAllViews()
        binding.profileSubEmpty.visibility = View.GONE
        binding.profileSubProgress.visibility = View.GONE
        binding.profileSubScreen.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.GONE

        val token = Session.token
        if (token == null) {
            showEmpty(
                when (section) {
                    Section.SAVED -> "Sign in to see places you've saved."
                    Section.REVIEWS -> "Sign in to see reviews you've written."
                    Section.PHOTOS -> "Sign in to see photos you've added."
                }
            )
            return
        }

        binding.profileSubProgress.visibility = View.VISIBLE
        activity.lifecycleScope.launch {
            when (section) {
                Section.SAVED -> {
                    val places = withContext(Dispatchers.IO) {
                        runCatching { PlacesApi.fetchSaved(token) }.getOrNull()
                    }
                    if (stillShowing(section)) renderSaved(places)
                }
                Section.REVIEWS -> {
                    val reviews = withContext(Dispatchers.IO) {
                        runCatching { PlacesApi.fetchMyReviews(token) }.getOrNull()
                    }
                    if (stillShowing(section)) renderReviews(reviews)
                }
                Section.PHOTOS -> {
                    val photos = withContext(Dispatchers.IO) {
                        runCatching { PlacesApi.fetchMyPhotos(token) }.getOrNull()
                    }
                    if (stillShowing(section)) renderPhotos(photos)
                }
            }
        }
    }

    /** The user hasn't backed out or switched sections while a load was in flight. */
    private fun stillShowing(section: Section): Boolean = isVisible() && current == section

    private fun renderSaved(places: List<Place>?) {
        binding.profileSubProgress.visibility = View.GONE
        if (places == null) { showEmpty(LOAD_ERROR); return }
        if (places.isEmpty()) {
            showEmpty("No saved places yet.\nTap Save on any place to bookmark it.")
            return
        }
        places.forEachIndexed { i, p ->
            if (i > 0) binding.profileSubList.addView(activity.placeDivider())
            val b = ItemPlaceBinding.inflate(activity.layoutInflater, binding.profileSubList, false)
            b.bindPlace(p) { openPlace(it) }
            binding.profileSubList.addView(b.root)
        }
    }

    private fun renderReviews(reviews: List<MyReview>?) {
        binding.profileSubProgress.visibility = View.GONE
        if (reviews == null) { showEmpty(LOAD_ERROR); return }
        if (reviews.isEmpty()) {
            showEmpty("You haven't written any reviews yet.")
            return
        }
        reviews.forEachIndexed { i, r ->
            if (i > 0) binding.profileSubList.addView(hairline())
            binding.profileSubList.addView(reviewCard(r))
        }
    }

    private fun renderPhotos(photos: List<MyPhoto>?) {
        binding.profileSubProgress.visibility = View.GONE
        if (photos == null) { showEmpty(LOAD_ERROR); return }
        if (photos.isEmpty()) {
            showEmpty("You haven't added any photos yet.\nAdd photos from any place to see them here.")
            return
        }
        // A simple 3-column grid; each thumbnail deep-links to its place.
        val cols = 3
        val gap = dp(3)
        val size = (activity.resources.displayMetrics.widthPixels - dp(16) * 2 - gap * (cols - 1)) / cols
        var row: LinearLayout? = null
        photos.forEachIndexed { i, ph ->
            if (i % cols == 0) {
                row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = gap; marginStart = dp(16); marginEnd = dp(16) }
                }
                binding.profileSubList.addView(row)
            }
            row!!.addView(ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.thumb_bg)
                clipToOutline = true
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (i % cols != 0) marginStart = gap
                }
                load(ph.url) { crossfade(true) }
                setOnClickListener { openPlaceById(ph.placeId) }
            })
        }
    }

    private fun showEmpty(message: String) {
        binding.profileSubProgress.visibility = View.GONE
        binding.profileSubList.removeAllViews()
        binding.profileSubEmpty.text = message
        binding.profileSubEmpty.visibility = View.VISIBLE
    }

    /** Leave the sub-screen and open a place we already hold in full. */
    private fun openPlace(p: Place) {
        hide()
        onOpenPlace(p)
    }

    /** Open a place we only know by id (from a review/photo): fetch it, then show detail. */
    private fun openPlaceById(placeId: String) {
        if (placeId.isBlank()) return
        activity.lifecycleScope.launch {
            val place = withContext(Dispatchers.IO) {
                runCatching { PlacesApi.fetchPlace(placeId, Session.token) }.getOrNull()
            }
            if (place == null) { activity.toast("Couldn't open that place"); return@launch }
            openPlace(place)
        }
    }

    /** A review the user wrote: place name/area heading, star line, and body. Taps open the place. */
    private fun reviewCard(r: MyReview): View {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            background = activity.themedRipple()
        }
        card.addView(TextView(activity).apply {
            text = r.placeName
            setTextColor(Color.parseColor("#202124"))
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(activity).apply {
            text = "★".repeat(r.rating) + "☆".repeat(5 - r.rating) +
                if (r.placeArea.isNotBlank()) "   ·   ${r.placeArea}" else ""
            setTextColor(Color.parseColor("#202124"))
            textSize = 13.5f
            setPadding(0, dp(3), 0, 0)
        })
        if (r.body.isNotBlank()) {
            card.addView(TextView(activity).apply {
                text = r.body
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 14f
                setPadding(0, dp(6), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        card.setOnClickListener { openPlaceById(r.placeId) }
        return card
    }

    /** A full-width hairline between cards (unlike placeDivider's icon-inset variant). */
    private fun hairline(): View = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
        setBackgroundColor(Color.parseColor("#EDEEF0"))
    }
}
