package com.wein.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.wein.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The place-detail screen: parallax header, rating/price/open status, landmark location,
 * the Directions/Call/Save/Share actions, About, reviews and the photo strip.
 *
 * Owns the currently-shown place and renders into the `detail*` views in [binding]. Calls back
 * to the [activity] only for shared helpers (dp/toast/themedRipple) and to leave for the map
 * (showMap + routeTo) when the user taps Directions. Extracted from MainActivity (Wave 2).
 */
internal class PlaceDetailController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private var currentPlace: Place? = null

    private data class Rev(val name: String, val stars: Int, val text: String)

    private val reviewPool = listOf(
        Rev("Rami K.", 5, "Great spot, exactly as described. Easy to find with the landmark directions."),
        Rev("Nour A.", 4, "Good service and lovely atmosphere. A bit busy on weekends."),
        Rev("Jad H.", 5, "One of my favourites in the area. Highly recommend."),
        Rev("Maya S.", 4, "Solid choice — parking can be tricky though."),
        Rev("Karim T.", 3, "Decent, but a little overpriced for what it is."),
        Rev("Lynn M.", 5, "Staff were super friendly. Will definitely come back."),
    )

    private fun dp(v: Int) = activity.dp(v)
    private fun toast(msg: String) = activity.toast(msg)

    fun isVisible(): Boolean = binding.placeDetailScreen.visibility == View.VISIBLE

    fun show(p: Place) {
        currentPlace = p
        bind(p)                       // instant: what we already have from the list
        binding.detailScroll.scrollTo(0, 0)
        binding.detailTopBar.setBackgroundColor(Color.TRANSPARENT)
        binding.detailTopTitle.alpha = 0f
        binding.detailHeader.translationY = 0f
        binding.placeDetailScreen.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.GONE

        // Then refresh from the backend: authoritative detail (phone, saved) + real reviews.
        if (p.id.isNotBlank()) {
            activity.lifecycleScope.launch {
                val full = withContext(Dispatchers.IO) { runCatching { PlacesApi.fetchPlace(p.id, Session.token) }.getOrNull() }
                val reviews = withContext(Dispatchers.IO) { runCatching { PlacesApi.fetchReviews(p.id) }.getOrNull() }.orEmpty()
                // Ignore if the user has since opened a different place.
                if (currentPlace?.id != p.id) return@launch
                if (full != null) { currentPlace = full; bind(full) }
                if (reviews.isNotEmpty()) renderReviews(reviews.map { Rev(it.author, it.rating, it.body) })
            }
        }
    }

    private fun renderReviews(reviews: List<Rev>) {
        binding.detailReviews.removeAllViews()
        reviews.forEach { binding.detailReviews.addView(reviewView(it)) }
    }

    /** Compose + post a review for the current place. Caller ensures the user is signed in. */
    fun promptWriteReview() {
        val p = currentPlace ?: return
        if (p.id.isBlank()) { toast("Reviews aren't available for this place yet"); return }
        val token = Session.token ?: return
        val pad = dp(20)
        val ratingBar = RatingBar(activity).apply { numStars = 5; stepSize = 1f; rating = 5f }
        val bodyInput = EditText(activity).apply {
            hint = "Share your experience…"
            setLines(3)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(ratingBar); addView(bodyInput)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Review ${p.name}")
            .setView(layout)
            .setPositiveButton("Post", null)   // null: validate below without auto-dismiss
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // Body is optional — a star rating alone is a valid review.
                submitReview(dialog, p.id, ratingBar.rating.toInt().coerceIn(1, 5),
                    bodyInput.text.toString().trim(), token)
            }
        }
        dialog.show()
    }

    private fun submitReview(dialog: AlertDialog, placeId: String, rating: Int, body: String, token: String) {
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { PlacesApi.postReview(placeId, rating, body, token) }.getOrDefault(false)
            }
            if (!ok) { activity.toast("Couldn't post your review. Are you still signed in?"); return@launch }
            activity.toast("Review posted — thank you!")
            dialog.dismiss()
            // Refresh from the backend (rating/count are recomputed server-side).
            val full = withContext(Dispatchers.IO) { runCatching { PlacesApi.fetchPlace(placeId) }.getOrNull() }
            val reviews = withContext(Dispatchers.IO) { runCatching { PlacesApi.fetchReviews(placeId) }.getOrNull() }.orEmpty()
            if (currentPlace?.id == placeId) {
                if (full != null) { currentPlace = full; bind(full) }
                if (reviews.isNotEmpty()) renderReviews(reviews.map { Rev(it.author, it.rating, it.body) })
            }
        }
    }

    fun hide() {
        binding.placeDetailScreen.visibility = View.GONE
        binding.bottomNav.visibility = View.VISIBLE
    }

    private fun directionsToCurrentPlace() {
        val p = currentPlace ?: return
        binding.placeDetailScreen.visibility = View.GONE
        activity.showMap()
        activity.routeTo(Landmark(p.name, "place", p.lat, p.lng))
    }

    private fun bind(p: Place) {
        binding.detailHeaderIcon.setImageResource(p.category.glyph)
        binding.detailName.text = p.name
        binding.detailTopTitle.text = p.name
        binding.detailPromoted.visibility = if (p.promoted) View.VISIBLE else View.GONE
        binding.detailRating.text =
            "${"%.1f".format(p.rating)} ★    %,d reviews".format(p.reviews)
        binding.detailMeta.text = buildString {
            append(p.category.label)
            if (p.priceText.isNotEmpty()) append(" · ").append(p.priceText)
            append(" · ").append(if (p.openNow) "Open now" else "Closed")
        }
        binding.detailLocation.text = "${p.landmark} · ${p.area}"
        val cat = p.category.label.lowercase(java.util.Locale.US)
        binding.detailAbout.text =
            (if (p.openNow) "Currently open. " else "Currently closed. ") +
            "${p.name} is a well-known $cat in ${p.area}. ${p.landmark} — " +
            "the kind of spot locals point you to by landmark, not by address."

        renderActions(p)

        // Reviews — sample for now; show() swaps in real backend reviews when available.
        val start = Math.abs(p.name.hashCode()) % reviewPool.size
        renderReviews((0 until 3).map { reviewPool[(start + it) % reviewPool.size] })

        // Photos (placeholders)
        binding.detailPhotos.removeAllViews()
        repeat(4) {
            binding.detailPhotos.addView(ImageView(activity).apply {
                setImageResource(p.category.glyph)
                alpha = 0.3f
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#3C4043"))
                background = AppCompatResources.getDrawable(context, R.drawable.thumb_bg)
                val pad = dp(22); setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(10) }
            })
        }
    }

    /** The Directions / Call / Save / Share row. Re-rendered on its own when Save toggles. */
    private fun renderActions(p: Place) {
        binding.actionsRow.removeAllViews()
        binding.actionsRow.addView(actionItem(R.drawable.ic_nav, "Directions", true) { directionsToCurrentPlace() })
        binding.actionsRow.addView(actionItem(R.drawable.ic_call, "Call", false) {
            val phone = p.phone
            if (phone.isNullOrBlank()) toast("No phone number yet")
            else activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        })
        binding.actionsRow.addView(
            actionItem(R.drawable.ic_bookmark, if (p.saved) "Saved" else "Save", filled = p.saved) { toggleSave() }
        )
        binding.actionsRow.addView(actionItem(R.drawable.ic_share, "Share", false) { toast("Share — coming soon") })
    }

    /** Save / un-save the current place. Optimistic: flip immediately, revert on failure. */
    private fun toggleSave() {
        val p = currentPlace ?: return
        if (p.id.isBlank()) { toast("This place can't be saved yet"); return }
        val token = Session.token
        if (token == null) { toast("Sign in to save places"); activity.promptSignIn(); return }

        val target = !p.saved
        val updated = p.copy(saved = target)
        currentPlace = updated
        renderActions(updated)                       // optimistic UI
        activity.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { PlacesApi.setSaved(p.id, target, token) }.getOrDefault(false)
            }
            if (currentPlace?.id != p.id) return@launch   // user moved on
            if (ok) {
                toast(if (target) "Saved to your places" else "Removed from saved")
            } else {
                currentPlace = p                     // revert
                renderActions(p)
                toast("Couldn't update. Are you still signed in?")
            }
        }
    }

    private fun actionItem(iconRes: Int, label: String, filled: Boolean, onClick: () -> Unit): View {
        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            background = activity.themedRipple()
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        item.addView(ImageView(activity).apply {
            setImageResource(iconRes)
            imageTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (filled) "#FFFFFF" else "#202124")
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor(if (filled) "#202124" else "#F1F3F4"))
            }
            val pad = dp(13); setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        })
        item.addView(TextView(activity).apply {
            text = label
            textSize = 12f
            setTextColor(Color.parseColor("#5F6368"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        item.setOnClickListener { onClick() }
        return item
    }

    private fun reviewView(r: Rev): View {
        val block = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(activity).apply {
            text = r.name.take(1)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#3C4043"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
        })
        head.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
            addView(TextView(activity).apply {
                text = r.name
                setTextColor(Color.parseColor("#202124"))
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = "★".repeat(r.stars) + "☆".repeat(5 - r.stars)
                setTextColor(Color.parseColor("#202124"))
                textSize = 12.5f
            })
        })
        block.addView(head)
        block.addView(TextView(activity).apply {
            text = r.text
            setTextColor(Color.parseColor("#5F6368"))
            textSize = 14f
            setPadding(0, dp(6), 0, 0)
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        return block
    }
}
