package com.wein.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.divider.MaterialDividerItemDecoration
import com.wein.app.databinding.ActivityMainBinding

/**
 * The Explore home: category chip row, search box, and the promoted-first place list
 * (a [RecyclerView][androidx.recyclerview.widget.RecyclerView] backed by [PlacesAdapter]).
 * Filtering/sorting is `filter/sort -> submitList`. Tapping a row bubbles up via [onOpenPlace].
 *
 * Renders into the explore* / chipsRow / placesList views in [binding]. Extracted from
 * MainActivity (Wave 2); screen routing (Explore/Contribute/Profile/Map) stays in the activity.
 */
internal class ExploreController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val onOpenPlace: (Place) -> Unit,
) {
    private var currentChip = 0   // selected category chip
    private val placesAdapter = PlacesAdapter { onOpenPlace(it) }

    private fun dp(v: Int) = activity.dp(v)

    fun setup() {
        binding.placesList.layoutManager = LinearLayoutManager(activity)
        binding.placesList.adapter = placesAdapter
        binding.placesList.addItemDecoration(
            MaterialDividerItemDecoration(activity, MaterialDividerItemDecoration.VERTICAL).apply {
                dividerColor = ContextCompat.getColor(activity, R.color.hairline)
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
            activity.hideKeyboard()
            renderPlaces()
        }
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
        val tv = TextView(activity).apply {
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
                val d = AppCompatResources.getDrawable(activity, chip.glyph)!!.mutate()
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
}
