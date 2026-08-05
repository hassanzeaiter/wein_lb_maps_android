package com.wein.app

import android.content.Context

/**
 * The signed-in user's session — the bearer token plus display fields — persisted in
 * SharedPreferences so login survives app restarts. In-memory mirror for quick reads.
 */
object Session {
    private const val PREFS = "wein_session"

    var token: String? = null
        private set
    var name: String? = null
        private set
    var email: String? = null
        private set

    val isLoggedIn: Boolean get() = token != null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        token = p.getString("token", null)
        name = p.getString("name", null)
        email = p.getString("email", null)
    }

    fun save(ctx: Context, token: String, name: String, email: String) {
        this.token = token; this.name = name; this.email = email
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token).putString("name", name).putString("email", email).apply()
    }

    fun clear(ctx: Context) {
        token = null; name = null; email = null
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
