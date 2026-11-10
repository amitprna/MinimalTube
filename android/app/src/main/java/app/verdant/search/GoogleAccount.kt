package app.verdant.search

import android.content.Context
import android.content.MutableContextWrapper
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

class GoogleAccount(private val context: Context) {
    suspend fun signIn(returning: Boolean = false): SignedInUser {
        val clientId=configuredGoogleClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
        val option = if (returning) GetGoogleIdOption.Builder().setServerClientId(clientId)
            .setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true).build()
        else GetSignInWithGoogleOption.Builder(clientId).build()
        val result = try { CredentialManager.create(context).getCredential(MutableContextWrapper(context), GetCredentialRequest.Builder().addCredentialOption(option).build()) }
        catch (_: NoCredentialException) { throw SearchFailure("no_google_account", "No eligible Google account was found. Add an account in Android Settings or choose Continue with Google again.") }
        val credential = result.credential as? CustomCredential ?: throw SearchFailure("sign_in_failed", "Google did not return an account.")
        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
            throw SearchFailure("sign_in_failed", "Google returned an unsupported credential.")
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        // The local credential is issued by Google Play services. No backend session
        // is created in BYOK mode; YouTube separately authorizes every account scope.
        val verified = SignedInUser(google.displayName ?: google.id,google.id)
        SignInSession.user = verified
        context.getSharedPreferences("verdant",0).edit().putString("connected-google-email",verified.email).apply()
        return verified
    }
    suspend fun signOut() {
        SignInSession.user = null
        context.getSharedPreferences("verdant",0).edit().remove("connected-google-email").remove("sync-google-changes").apply()
        CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
    }
}


/** OAuth identifies this signed app; it is supplied by the developer, never the user vault. */
fun configuredGoogleClientId(value: String): String {
    val clientId=value.trim()
    if(!clientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")))
        throw SearchFailure("sign_in_configuration","Google sign-in is unavailable in this build. Install the configured release or contact the app maintainer.")
    return clientId
}
