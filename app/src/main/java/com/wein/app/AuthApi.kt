package com.wein.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Shared backend base URL (emulator → host `wrangler dev`; deployed Worker on a device). */
internal const val API_BASE_URL = "http://10.0.2.2:8787"

/** Result of a signup/login attempt. */
sealed class AuthResult {
    data class Success(val token: String, val name: String, val email: String) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/** Auth endpoints: POST /auth/signup, /auth/login, /auth/logout. Call off the main thread. */
object AuthApi {

    fun signup(email: String, name: String, password: String): AuthResult =
        post("/auth/signup", JSONObject().put("email", email).put("name", name).put("password", password))

    fun login(email: String, password: String): AuthResult =
        post("/auth/login", JSONObject().put("email", email).put("password", password))

    /** Best-effort server-side logout; the local session is cleared regardless. */
    fun logout(token: String) {
        runCatching {
            val conn = (URL("$API_BASE_URL/auth/logout").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 6000; readTimeout = 6000
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.responseCode
            conn.disconnect()
        }
    }

    private fun post(path: String, body: JSONObject): AuthResult {
        val conn = (URL("$API_BASE_URL$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000; readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (code in 200..299 && json != null) {
                val user = json.getJSONObject("user")
                AuthResult.Success(json.getString("token"), user.getString("name"), user.getString("email"))
            } else {
                AuthResult.Error(friendly(json?.optString("error")))
            }
        } catch (e: Exception) {
            AuthResult.Error("Couldn't reach the server. Check your connection.")
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(error: String?): String = when (error) {
        "email_taken" -> "That email is already registered."
        "invalid_credentials" -> "Wrong email or password."
        "invalid_input" -> "Enter a valid email and a password of 6+ characters."
        else -> "Something went wrong. Please try again."
    }
}
