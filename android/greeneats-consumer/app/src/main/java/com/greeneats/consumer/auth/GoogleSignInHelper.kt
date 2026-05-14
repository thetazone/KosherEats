package com.greeneats.consumer.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.greeneats.consumer.BuildConfig

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

        // Primary: GetGoogleIdOption with filterByAuthorizedAccounts=false.
        // This avoids GetSignInWithGoogleOption's aggressive reauth path
        // which can fail right after a new SHA-1 is registered while Firebase
        // is still propagating the authorization grant downstream.
        val primary = GetCredentialRequest.Builder()
            .addCredentialOption(
                GetGoogleIdOption.Builder()
                    .setServerClientId(webClientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
            )
            .build()

        return try {
            parseCredential(credentialManager.getCredential(context, primary))
        } catch (e: GetCredentialCancellationException) {
            Result.failure(IllegalStateException("cancelled"))
        } catch (e: NoCredentialException) {
            Result.failure(IllegalStateException("Google Sign-In failed. Ensure a Google account is signed in on this device."))
        } catch (e: GetCredentialException) {
            // Wrap the underlying error in the message so it surfaces in the UI
            // rather than silently being suppressed as a generic failure.
            val detail = e.errorMessage?.toString() ?: e.javaClass.simpleName
            Result.failure(IllegalStateException("Google Sign-In failed: $detail"))
        } catch (e: GoogleIdTokenParsingException) {
            Result.failure(e)
        }
    }

    private fun parseCredential(
        result: androidx.credentials.GetCredentialResponse,
    ): Result<GoogleSignInResult> {
        val credential = result.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val token = GoogleIdTokenCredential.createFrom(credential.data)
            return Result.success(
                GoogleSignInResult(
                    idToken = token.idToken,
                    firstName = token.givenName.orEmpty(),
                    lastName = token.familyName.orEmpty(),
                )
            )
        }
        return Result.failure(IllegalStateException("Unexpected credential type: ${credential.type}"))
    }
}
