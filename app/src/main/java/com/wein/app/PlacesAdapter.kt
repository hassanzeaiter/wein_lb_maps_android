package com.wein.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.wein.app.databinding.ItemPlaceBinding

/**
 * The Explore directory list. One [item_place][ItemPlaceBinding] row per [Place];
 * taps bubble up through [onClick]. Backed by a [ListAdapter] so filtering/sorting is
 * just a `submitList(...)` and DiffUtil animates the change.
 */
class PlacesAdapter(private val onClick: (Place) -> Unit) :
    ListAdapter<Place, PlacesAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemPlaceBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Place) {
            b.thumb.setImageResource(p.category.glyph)
            b.name.text = p.name
            b.promoted.visibility = if (p.promoted) View.VISIBLE else View.GONE
            b.rating.text = "${"%.1f".format(p.rating)} ★   ${formatCount(p.reviews)}"
            b.meta.text = buildString {
                append(p.category.label)
                if (p.priceText.isNotEmpty()) append(" · ").append(p.priceText)
                append(" · ").append(p.area)
            }
            b.root.setOnClickListener { onClick(p) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Place>() {
            // Place names are unique in the seed data, so they identify a row.
            override fun areItemsTheSame(a: Place, b: Place) = a.name == b.name
            override fun areContentsTheSame(a: Place, b: Place) = a == b
        }
    }
}

/** "1840" → "1.8k". Shared by the list row and the place-detail header. */
internal fun formatCount(n: Int): String =
    if (n >= 1000) "%.1fk".format(n / 1000.0).replace(".0k", "k") else n.toString()
