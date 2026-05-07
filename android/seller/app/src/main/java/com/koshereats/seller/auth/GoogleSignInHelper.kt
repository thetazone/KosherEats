package com.koshereats.seller.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.koshereats.seller.BuildConfig

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
        val googleIdOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        Log.d(TAG, "getCredential starting, context=${context.javaClass.name}")
        return try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            Log.d(TAG, "credential type=${credential.type}")
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val token = GoogleIdTokenCredential.createFrom(credential.data)
                Log.d(TAG, "Google sign-in success, name=${token.givenName}")
                Result.success(
                    GoogleSignInResult(
                        idToken = token.idToken,
                        firstName = token.givenName.orEmpty(),
                        lastName = token.familyName.orEmpty(),
                    )
                )
            } else {
                Log.e(TAG, "Unexpected credential type: ${credential.type}")
                Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }
        } catch (e: GetCredentialException) {
            Log.e(TAG, "GetCredentialException: ${e.type} — ${e.message}", e)
            Result.failure(e)
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "GoogleIdTokenParsingException: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception: ${e.javaClass.name} — ${e.message}", e)
            Result.failure(e)
        }
    }
}
