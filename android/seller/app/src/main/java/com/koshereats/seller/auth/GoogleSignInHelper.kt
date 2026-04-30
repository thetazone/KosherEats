package com.koshereats.seller.auth

import android.content.Context
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

        return try {
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val token = GoogleIdTokenCredential.createFrom(credential.data)
                Result.success(
                    GoogleSignInResult(
                        idToken = token.idToken,
                        firstName = token.givenName.orEmpty(),
                        lastName = token.familyName.orEmpty(),
                    )
                )
            } else {
                Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
            }
        } catch (e: GetCredentialException) {
            Result.failure(e)
        } catch (e: GoogleIdTokenParsingException) {
            Result.failure(e)
        }
    }
}
