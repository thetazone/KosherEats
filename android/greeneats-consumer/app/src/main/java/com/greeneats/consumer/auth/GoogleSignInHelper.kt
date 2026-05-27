package com.greeneats.consumer.auth

import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialInterruptedException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.greeneats.consumer.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GoogleSignInResult(
    val idToken: String,
    val firstName: String,
    val lastName: String,
)

object GoogleSignInHelper {
    private const val TAG = "GoogleSignInHelper"

    /** Observable loading state so callers can show/hide a spinner. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    suspend fun signIn(context: Context): Result<GoogleSignInResult> {
        val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (webClientId.isBlank()) {
            Log.e(TAG, "GOOGLE_WEB_CLIENT_ID is blank — cannot start sign-in")
            return Result.failure(IllegalStateException("GOOGLE_WEB_CLIENT_ID not set in local.properties"))
        }

        _isLoading.value = true

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
            val response = credentialManager.getCredential(context, primary)
            parseCredential(response)
        } catch (e: GetCredentialCancellationException) {
            Log.d(TAG, "User cancelled sign-in")
            Result.failure(IllegalStateException("cancelled"))
        } catch (e: NoCredentialException) {
            Log.w(TAG, "No credential available", e)
            Result.failure(IllegalStateException("Google Sign-In failed. Ensure a Google account is signed in on this device."))
        } catch (e: GetCredentialUnsupportedException) {
            Log.e(TAG, "Credential Manager not supported on this device", e)
            Result.failure(IllegalStateException("Google Sign-In is not supported on this device."))
        } catch (e: GetCredentialProviderConfigurationException) {
            Log.e(TAG, "Credential provider misconfigured", e)
            Result.failure(IllegalStateException("Google Sign-In is not configured correctly. Please try again later."))
        } catch (e: GetCredentialInterruptedException) {
            Log.w(TAG, "Sign-in interrupted", e)
            Result.failure(IllegalStateException("Sign-in was interrupted. Please try again."))
        } catch (e: GetCredentialException) {
            // Wrap the underlying error in the message so it surfaces in the UI
            // rather than silently being suppressed as a generic failure.
            val detail = e.errorMessage?.toString() ?: e.javaClass.simpleName
            Log.e(TAG, "GetCredentialException: $detail", e)
            Result.failure(IllegalStateException("Google Sign-In failed: $detail"))
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Failed to parse Google ID token", e)
            Result.failure(IllegalStateException("Failed to read Google account credentials. Please try again."))
        } catch (e: Exception) {
            // Catch-all so the caller never sees an unhandled exception
            Log.e(TAG, "Unexpected error during sign-in", e)
            Result.failure(IllegalStateException("An unexpected error occurred. Please try again."))
        } finally {
            _isLoading.value = false
        }
    }

    private fun parseCredential(
        result: GetCredentialResponse,
    ): Result<GoogleSignInResult> {
        val credential = result.credential

        // Validate that we got the expected credential type
        if (credential !is CustomCredential) {
            Log.w(TAG, "Received non-CustomCredential: ${credential.javaClass.simpleName}")
            return Result.failure(
                IllegalStateException("Unexpected credential format. Expected Google ID token but received ${credential.javaClass.simpleName}.")
            )
        }

        if (credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            Log.w(TAG, "Wrong CustomCredential type: ${credential.type}")
            return Result.failure(
                IllegalStateException("Unexpected credential type: ${credential.type}")
            )
        }

        return try {
            val token = GoogleIdTokenCredential.createFrom(credential.data)

            // Validate the ID token is non-empty
            if (token.idToken.isBlank()) {
                Log.e(TAG, "Received blank idToken from Google")
                return Result.failure(
                    IllegalStateException("Received an empty token from Google. Please try again.")
                )
            }

            Result.success(
                GoogleSignInResult(
                    idToken = token.idToken,
                    firstName = token.givenName.orEmpty(),
                    lastName = token.familyName.orEmpty(),
                )
            )
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Failed to parse GoogleIdTokenCredential", e)
            Result.failure(
                IllegalStateException("Failed to read Google credentials. Please try again.")
            )
        }
    }
}
