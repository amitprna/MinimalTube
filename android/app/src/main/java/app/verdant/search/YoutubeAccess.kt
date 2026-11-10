package app.verdant.search

import android.accounts.Account
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Incremental authorization; no access or refresh tokens are persisted. */
class YoutubeAccess(private val activity: ComponentActivity) {
    private val client = Identity.getAuthorizationClient(activity)
    private var pending: CancellableContinuation<String>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try { complete(client.getAuthorizationResultFromIntent(result.data)) }
        catch (_: Exception) { fail("YouTube permission was not granted. You can continue using search.") }
    }
    private fun complete(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) { fail("Google did not provide YouTube access. Please reconnect."); return }
        pending?.takeIf { it.isActive }?.resume(token); pending = null
    }
    private fun fail(message: String) {
        pending?.takeIf { it.isActive }?.resumeWithException(SearchFailure("authorization", message)); pending = null
    }
    /** With [interactive] false nothing is shown: the call fails quietly if Google would need a sign-in or permission prompt. */
    suspend fun token(write: Boolean = false, interactive: Boolean = true): String {
        val prefs=activity.getSharedPreferences("verdant",0)
        var email=SignInSession.user?.email ?: prefs.getString("connected-google-email",null)
        if (email == null) {
            if (!interactive) throw SearchFailure("sign_in_required","Sign in to Google first.")
            GoogleAccount(activity).signIn()
            prefs.edit().putBoolean("google-restore",true).apply()
            email=SignInSession.user!!.email
        }
        val accountEmail=email
        return suspendCancellableCoroutine { continuation ->
            if (pending != null) { continuation.resumeWithException(SearchFailure("busy","An account request is already open.")); return@suspendCancellableCoroutine }
            pending = continuation
            continuation.invokeOnCancellation { if (pending === continuation) pending = null }
            val scope = if (write) "https://www.googleapis.com/auth/youtube.force-ssl" else "https://www.googleapis.com/auth/youtube.readonly"
            val request = AuthorizationRequest.builder().setAccount(Account(accountEmail,"com.google"))
                .setRequestedScopes(listOf(Scope(scope))).build()
            client.authorize(request).addOnSuccessListener { result ->
                if (pending !== continuation || !continuation.isActive) return@addOnSuccessListener
                if (result.hasResolution()) {
                    if (!interactive) { fail("Google needs your permission again."); return@addOnSuccessListener }
                    try { launcher.launch(IntentSenderRequest.Builder(result.pendingIntent!!.intentSender).build()) }
                    catch (_: Exception) { fail("Could not open the Google permission prompt.") }
                } else complete(result)
            }.addOnFailureListener { if (pending === continuation) fail("Could not authorize YouTube. Check your account and Google Play services.") }
        }
    }
}

