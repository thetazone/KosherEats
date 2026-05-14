package com.greeneats.seller.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.greeneats.seller.BuildConfig

data class GoogleSignInResult(
    val idToken: String,
    val firstName: String,
    val lastName: String,
)

object GoogleSignInHelper {
    private const val TAG = "GoogleSignIn"
    suspend fun signIn(context: Context): Result<GoogleSignInResult> {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) {
            return Result.failure(IllegalStateException("GOOGLE_WEB_CLIENT_ID not set in local.properties"))
        }

        val credentialManager = CredentialManager.create(context)

        // SignInWithGoogleOption shows the full Google branded account chooser
        // (matching the screen users see on web/desktop sign-ins), with all
        // available Google accounts and a "Use another account" entry. The
        // older GetGoogleIdOption bottom-sheet often auto-uses the most-recent
        // account and hides the picker — not what we want for "Continue with
        // Google."
        val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        Log.d(TAG, "getCredential starting (SignInWithGoogleOption), context=${context.javaClass.name}")
        return try {
            parseCredential(credentialManager.getCredential(context, request))
        } catch (e: GetCredentialException) {
            Log.w(TAG, "SignInWithGoogleOption failed (${e.type}), retrying with GoogleIdOption")
            try {
                val fallback = GetCredentialRequest.Builder()
                    .addCredentialOption(
                        GetGoogleIdOption.Builder()
                            .setServerClientId(webClientId)
                            .setFilterByAuthorizedAccounts(false)
                            .setAutoSelectEnabled(false)
                            .build()
                    )
                    .build()
                parseCredential(credentialManager.getCredential(context, fallback))
            } catch (e2: GetCredentialException) {
                Log.e(TAG, "GoogleIdOption fallback also failed: ${e2.type} — ${e2.message}", e2)
                Result.failure(e2)
            }
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "GoogleIdTokenParsingException: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception: ${e.javaClass.name} — ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseCredential(
        result: androidx.credentials.GetCredentialResponse,
    ): Result<GoogleSignInResult> {
        val credential = result.credential
        Log.d(TAG, "credential type=${credential.type}")
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val token = GoogleIdTokenCredential.createFrom(credential.data)
            Log.d(TAG, "Google sign-in success, name=${token.givenName}")
            return Result.success(
                GoogleSignInResult(
                    idToken = token.idToken,
                    firstName = token.givenName.orEmpty(),
                    lastName = token.familyName.orEmpty(),
                )
            )
        }
        Log.e(TAG, "Unexpected credential type: ${credential.type}")
        return Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
    }
}
