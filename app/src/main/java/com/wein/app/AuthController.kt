package com.wein.app

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.wein.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign in / sign up and the Profile header state. A minimal dialog-based flow backed by the
 * backend auth endpoints via [AuthApi]; the token is persisted in [Session]. Extracted like
 * the other feature controllers — the activity just forwards the Sign-in button here.
 */
internal class AuthController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private fun dp(v: Int) = activity.dp(v)

    /** Reflect the current session in the Profile header + button. Call after login/logout. */
    fun refreshProfileHeader() {
        if (Session.isLoggedIn) {
            binding.profileName.text = Session.name
            binding.profileSubtitle.text = Session.email
            binding.signInBtn.text = "Sign out"
        } else {
            binding.profileName.text = "Guest"
            binding.profileSubtitle.text = "Sign in to save places & review"
            binding.signInBtn.text = "Sign in"
        }
    }

    fun onSignInClicked() {
        if (Session.isLoggedIn) confirmSignOut() else promptSignIn()
    }

    private fun promptSignIn() {
        val pad = dp(20)
        val email = EditText(activity).apply {
            hint = "Email"; inputType = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or InputType.TYPE_CLASS_TEXT
        }
        val name = EditText(activity).apply {
            hint = "Name (for sign up)"; inputType = InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_CLASS_TEXT
        }
        val password = EditText(activity).apply {
            hint = "Password"; inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
        }
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
            addView(email); addView(name); addView(password)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Sign in to Wein")
            .setView(layout)
            .setPositiveButton("Log in", null)   // null: override below so errors don't auto-dismiss
            .setNeutralButton("Sign up", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                submit(dialog, isSignup = false, email.text.toString().trim(),
                    name.text.toString().trim(), password.text.toString())
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                submit(dialog, isSignup = true, email.text.toString().trim(),
                    name.text.toString().trim(), password.text.toString())
            }
        }
        dialog.show()
    }

    private fun submit(dialog: AlertDialog, isSignup: Boolean, email: String, name: String, password: String) {
        if (!email.contains("@") || password.length < 6 || (isSignup && name.isEmpty())) {
            activity.toast("Enter a valid email, a 6+ char password" + if (isSignup) ", and a name." else ".")
            return
        }
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (isSignup) AuthApi.signup(email, name, password) else AuthApi.login(email, password)
            }
            when (result) {
                is AuthResult.Success -> {
                    Session.save(activity, result.token, result.name, result.email)
                    refreshProfileHeader()
                    activity.toast("Welcome, ${result.name}!")
                    dialog.dismiss()
                }
                is AuthResult.Error -> activity.toast(result.message)
            }
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(activity)
            .setTitle("Sign out?")
            .setMessage("You'll need to sign in again to save places and write reviews.")
            .setPositiveButton("Sign out") { _, _ ->
                val token = Session.token
                if (token != null) activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { AuthApi.logout(token) }
                }
                Session.clear(activity)
                refreshProfileHeader()
                activity.toast("Signed out")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
