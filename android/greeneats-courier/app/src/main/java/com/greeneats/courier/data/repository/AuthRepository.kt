package com.greeneats.courier.data.repository

import com.greeneats.courier.data.api.ApiService
import com.greeneats.courier.data.api.TokenStore
import com.greeneats.courier.data.models.AuthResponse
import com.greeneats.courier.data.models.CourierProfile
import com.greeneats.courier.data.models.CourierRegisterRequest
import com.greeneats.courier.data.models.EmailCheckRequest
import com.greeneats.courier.data.models.EmailCheckResponse
import com.greeneats.courier.data.models.LoginRequest
import com.greeneats.courier.data.models.PhoneStartRequest
import com.greeneats.courier.data.models.PhoneVerifyRequest
import com.greeneats.courier.data.models.SocialLoginRequest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AuthRepository is the thin layer between ViewModels and the API + token
 * storage. It's responsible for persisting tokens on successful auth and
 * wiping them on logout — ViewModels should never touch TokenStore directly.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val tokens: TokenStore,
) {
    suspend fun signup(
        email: String,
        password: String,
        firstName: String,
        lastName: String,
        phone: String,
    ): Result<AuthResponse> = runCatching {
        val response = api.registerCourier(
            CourierRegisterRequest(email, password, firstName, lastName, phone)
        )
        val body = response.body()
        if (response.isSuccessful && body != null) {
            tokens.save(body.token, body.refreshToken)
            body
        } else {
            throw IllegalStateException(errorMessage(response, "Signup failed"))
        }
    }

    suspend fun login(email: String, password: String): Result<AuthResponse> = runCatching {
        val response = api.login(LoginRequest(email, password))
        val body = response.body()
        if (response.isSuccessful && body != null) {
            // Block non-courier accounts from logging into the driver app.
            if (body.user.role != "courier") {
                throw IllegalStateException("This account is not a courier account")
            }
            tokens.save(body.token, body.refreshToken)
            body
        } else {
            throw IllegalStateException(errorMessage(response, "Login failed"))
        }
    }

    suspend fun profile(): Result<CourierProfile> = runCatching {
        val response = api.getProfile()
        response.body() ?: throw IllegalStateException(errorMessage(response, "Failed to load profile"))
    }

    suspend fun isAuthenticated(): Boolean = tokens.isAuthenticated()

    suspend fun logout() = tokens.clear()

    suspend fun deleteAccount(): Result<Unit> = runCatching {
        val response = api.deleteAccount()
        if (!response.isSuccessful) {
            throw IllegalStateException(errorMessage(response, "Couldn't delete account"))
        }
        tokens.clear()
    }

    suspend fun checkEmail(email: String): Result<EmailCheckResponse> = runCatching {
        val response = api.checkEmail(EmailCheckRequest(email))
        response.body() ?: throw IllegalStateException(errorMessage(response, "Couldn't check email"))
    }

    suspend fun startPhoneLogin(phone: String): Result<Unit> = runCatching {
        val response = api.phoneStart(PhoneStartRequest(phone))
        if (!response.isSuccessful) {
            throw IllegalStateException(errorMessage(response, "Couldn't send code"))
        }
    }

    suspend fun verifyPhoneLogin(
        phone: String,
        code: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
    ): Result<AuthResponse> = runCatching {
        val response = api.phoneVerify(
            PhoneVerifyRequest(
                phone = phone,
                code = code,
                firstName = firstName,
                lastName = lastName,
                email = email,
            )
        )
        val body = response.body()
        if (response.isSuccessful && body != null) {
            if (body.user.role != "courier") {
                throw IllegalStateException("This phone number isn't linked to a courier account")
            }
            tokens.save(body.token, body.refreshToken)
            body
        } else {
            throw IllegalStateException(errorMessage(response, "Verification failed"))
        }
    }

    suspend fun socialLogin(
        provider: String,
        token: String,
        firstName: String,
        lastName: String,
    ): Result<AuthResponse> = runCatching {
        val response = api.socialLogin(
            SocialLoginRequest(
                provider = provider,
                token = token,
                firstName = firstName,
                lastName = lastName,
            )
        )
        val body = response.body()
        if (response.isSuccessful && body != null) {
            if (body.user.role != "courier") {
                throw IllegalStateException("This account is not a courier account")
            }
            tokens.save(body.token, body.refreshToken)
            body
        } else {
            throw IllegalStateException(errorMessage(response, "Social login failed"))
        }
    }
}
