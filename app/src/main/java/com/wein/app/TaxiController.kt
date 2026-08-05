package com.wein.app

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.wein.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The in-app taxi (ride-hailing) flow — simulation only: a ride-tier picker with upfront
 * fares, a "finding your driver" state, and the assigned-driver card. Renders into
 * `binding.taxiPanel` and drives itself; it only calls back to the [activity] for the shared
 * `dp`/`toast` helpers and its coroutine scope.
 *
 * Extracted from MainActivity (Wave 2). Real dispatch + a driver app are tracked separately.
 */
internal class TaxiController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private data class TaxiTier(
        val name: String, val blurb: String, val seats: Int,
        val base: Double, val perKm: Double, val perMin: Double, val minFare: Double,
    )

    // Fares in USD (Lebanon's economy is dollarized). Tuned to feel realistic for Beirut.
    private val taxiTiers = listOf(
        TaxiTier("Economy", "Affordable everyday rides", 4, 1.50, 0.50, 0.08, 3.0),
        TaxiTier("Comfort", "Newer cars, more legroom", 4, 2.50, 0.70, 0.10, 4.5),
        TaxiTier("Van XL", "Extra seats for groups", 6, 3.50, 0.90, 0.12, 6.0),
    )

    private data class Driver(
        val name: String, val car: String, val color: String, val plate: String, val rating: Double,
    )

    private val driverPool = listOf(
        Driver("Ziad", "Kia Rio", "White", "B 234 561", 4.9),
        Driver("Rami", "Toyota Corolla", "Silver", "G 118 902", 4.8),
        Driver("Antoine", "Hyundai Accent", "Grey", "J 447 330", 4.9),
        Driver("Khaled", "Nissan Sunny", "Black", "M 903 214", 4.7),
        Driver("Elie", "Kia Cerato", "White", "N 655 118", 5.0),
    )

    private var tierIndex = 0
    private var requested = false
    private var job: Job? = null

    private fun dp(v: Int) = activity.dp(v)
    private fun toast(msg: String) = activity.toast(msg)

    /** Cancel any in-flight request and hide the panel (sheet dismissed or route re-requested). */
    fun reset() {
        job?.cancel()
        requested = false
        binding.taxiPanel.visibility = View.GONE
    }

    /** Cancel just the pending "finding driver" coroutine (e.g. on activity stop). */
    fun cancelPending() {
        job?.cancel()
    }

    /** Upfront fare estimate: base + distance + time, floored at the tier minimum. */
    private fun fareFor(tier: TaxiTier, meters: Double, seconds: Double): Double {
        val f = tier.base + tier.perKm * (meters / 1000.0) + tier.perMin * (seconds / 60.0)
        return maxOf(f, tier.minFare)
    }

    /** A believable, stable pickup ETA (minutes) — varies by tier and destination. */
    private fun pickupEta(tier: TaxiTier, dest: Landmark): Int {
        val h = Math.abs(dest.name.hashCode())
        return when (tier.name) {
            "Economy" -> 2 + h % 4
            "Comfort" -> 3 + h % 4
            else -> 5 + h % 5
        }
    }

    /** Ride-picker: the list of tiers with fares, and a Request button. */
    fun render(dest: Landmark, result: RouteResult) {
        val panel = binding.taxiPanel
        panel.removeAllViews()
        panel.addView(taxiLabel("CHOOSE A RIDE"))
        taxiTiers.forEachIndexed { i, t ->
            panel.addView(taxiTierRow(i, t, dest, result))
        }
        val tier = taxiTiers[tierIndex]
        panel.addView(taxiPrimaryButton("Request ${tier.name}") { requestTaxi(dest, result) })
        panel.addView(TextView(activity).apply {
            text = "Fares are estimates in USD · driver assigned on request"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        })
    }

    private fun taxiTierRow(index: Int, tier: TaxiTier, dest: Landmark, result: RouteResult): View {
        val selected = index == tierIndex
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (selected) "#F1F3F4" else "#FFFFFF"))
                setStroke(dp(if (selected) 2 else 1),
                    Color.parseColor(if (selected) "#202124" else "#EDEEF0"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            isClickable = true
        }
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_taxi)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#202124"))
            background = AppCompatResources.getDrawable(context, R.drawable.thumb_bg)
            val p = dp(9); setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(12) }
        })
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        nameRow.addView(TextView(activity).apply {
            text = tier.name
            setTextColor(Color.parseColor("#202124"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        nameRow.addView(TextView(activity).apply {
            text = "· ${pickupEta(tier, dest)} min away"
            setTextColor(Color.parseColor("#1A73E8"))
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        })
        col.addView(nameRow)
        col.addView(TextView(activity).apply {
            text = "${tier.blurb} · ${tier.seats} seats"
            setTextColor(Color.parseColor("#5F6368"))
            textSize = 13f
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(activity).apply {
            text = "$" + "%.2f".format(fareFor(tier, result.distanceMeters, result.durationSeconds))
            setTextColor(Color.parseColor("#202124"))
            textSize = 16.5f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.setOnClickListener {
            tierIndex = index
            render(dest, result)
        }
        return row
    }

    /** Fired by "Request": simulate finding a nearby driver, then show the driver card. */
    private fun requestTaxi(dest: Landmark, result: RouteResult) {
        requested = true
        val tier = taxiTiers[tierIndex]
        renderTaxiFinding(tier, dest, result)
        job?.cancel()
        job = activity.lifecycleScope.launch {
            delay(2600)
            renderTaxiDriver(tier, dest, result)
        }
    }

    private fun renderTaxiFinding(tier: TaxiTier, dest: Landmark, result: RouteResult) {
        val panel = binding.taxiPanel
        panel.removeAllViews()
        panel.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(10))
            addView(android.widget.ProgressBar(activity).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            })
            addView(TextView(activity).apply {
                text = "Finding your ${tier.name} driver…"
                setTextColor(Color.parseColor("#202124"))
                textSize = 16.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            })
            addView(TextView(activity).apply {
                text = "Contacting nearby drivers"
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 13.5f
                setPadding(0, dp(3), 0, 0)
            })
        })
        panel.addView(taxiOutlineButton("Cancel request") { cancelTaxi(dest, result) })
    }

    private fun renderTaxiDriver(tier: TaxiTier, dest: Landmark, result: RouteResult) {
        val driver = driverPool[Math.abs((dest.name + tier.name).hashCode()) % driverPool.size]
        val eta = pickupEta(tier, dest)
        val fare = fareFor(tier, result.distanceMeters, result.durationSeconds)
        val panel = binding.taxiPanel
        panel.removeAllViews()

        panel.addView(TextView(activity).apply {
            text = "${driver.name} is on the way"
            setTextColor(Color.parseColor("#202124"))
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(TextView(activity).apply {
            text = "Arriving in $eta min · ${tier.name} · $" + "%.2f".format(fare)
            setTextColor(Color.parseColor("#5F6368"))
            textSize = 14f
            setPadding(0, dp(3), 0, dp(2))
        })

        // Driver card: avatar + name/rating on the left, car + plate on the right.
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#F1F3F4"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        card.addView(TextView(activity).apply {
            text = driver.name.take(1)
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#3C4043"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(12) }
        })
        card.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(activity).apply {
                text = driver.name
                setTextColor(Color.parseColor("#202124"))
                textSize = 15.5f
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(activity).apply {
                text = "★ ${"%.1f".format(driver.rating)} · ${driver.color} ${driver.car}"
                setTextColor(Color.parseColor("#5F6368"))
                textSize = 13f
                setPadding(0, dp(2), 0, 0)
            })
        })
        card.addView(TextView(activity).apply {
            text = driver.plate
            setTextColor(Color.parseColor("#202124"))
            textSize = 14f
            setTypeface(android.graphics.Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
                setStroke(dp(1), Color.parseColor("#DADCE0"))
            }
        })
        panel.addView(card)

        // Actions: Call (outline) + Cancel ride (outline, red text).
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        actions.addView(taxiOutlineButton("Call ${driver.name}", weight = 1f, marginEnd = dp(8)) {
            toast("Calling ${driver.name}…")
        })
        actions.addView(taxiOutlineButton("Cancel ride", weight = 1f, textColor = "#D93025") {
            cancelTaxi(dest, result)
        })
        panel.addView(actions)
    }

    private fun cancelTaxi(dest: Landmark, result: RouteResult) {
        job?.cancel()
        requested = false
        render(dest, result)
    }

    private fun taxiLabel(text: String): View = TextView(activity).apply {
        this.text = text
        setTextColor(Color.parseColor("#5F6368"))
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.08f
    }

    private fun taxiPrimaryButton(label: String, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#FFFFFF"))
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(Color.parseColor("#202124"))
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ).apply { topMargin = dp(14) }
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun taxiOutlineButton(
        label: String, weight: Float = 0f, marginEnd: Int = 0,
        textColor: String = "#202124", onClick: () -> Unit,
    ): View = TextView(activity).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(textColor))
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor("#FFFFFF"))
            setStroke(dp(1), Color.parseColor("#DADCE0"))
        }
        layoutParams =
            if (weight > 0f)
                LinearLayout.LayoutParams(0, dp(48), weight).apply { this.marginEnd = marginEnd }
            else
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                    .apply { topMargin = dp(12) }
        isClickable = true
        setOnClickListener { onClick() }
    }
}
