package com.koshereats.courier.data.repository

import com.koshereats.courier.data.api.ApiService
import com.koshereats.courier.data.api.TokenStore
import com.koshereats.courier.data.models.AuthResponse
import com.koshereats.courier.data.models.CourierProfile
import com.koshereats.courier.data.models.CourierRegisterRequest
import com.koshereats.courier.data.models.LoginRequest
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
}
