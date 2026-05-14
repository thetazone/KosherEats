package com.koshereats.courier.data.repository

import com.koshereats.courier.data.api.ApiService
import com.koshereats.courier.data.models.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CourierRepository owns everything post-login: onboarding steps, online
 * toggle, live location, delivery marketplace, payouts.
 *
 * Methods return Result<T> instead of Flow<Resource<T>> because these are
 * all one-shot operations driven from the UI; the dashboard does its own
 * polling loop with regular coroutines.
 */
@Singleton
class CourierRepository @Inject constructor(
    private val api: ApiService,
) {
    // ── Onboarding ──────────────────────────────────────────

    suspend fun startPhoneOtp(phoneE164: String): Result<Unit> = runCatching {
        val r = api.phoneStart(com.koshereats.courier.data.models.PhoneStartRequest(phone = phoneE164))
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't send code"))
    }

    suspend fun verifyPhone(phoneE164: String, code: String): Result<Unit> = runCatching {
        val r = api.verifyPhone(
            com.koshereats.courier.data.models.PhoneVerifyRequest(
                phone = phoneE164,
                code = code,
            )
        )
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Verification failed"))
    }

    suspend fun updateVehicle(body: UpdateVehicleRequest): Result<CourierProfile> = runCatching {
        val r = api.updateVehicle(body)
        r.body() ?: throw IllegalStateException(errorMessage(r, "Failed to update vehicle"))
    }

    suspend fun updateDocuments(body: UpdateDocumentsRequest): Result<CourierProfile> = runCatching {
        val r = api.updateDocuments(body)
        r.body() ?: throw IllegalStateException(errorMessage(r, "Failed to submit documents"))
    }

    suspend fun profile(): Result<CourierProfile> = runCatching {
        val r = api.getProfile()
        if (r.code() == 403) throw RoleMismatchException("This account does not have courier access. Please contact support.")
        r.body() ?: throw IllegalStateException(errorMessage(r, "Failed to load profile"))
    }

    // ── Live state ──────────────────────────────────────────

    suspend fun setOnline(online: Boolean, lat: Double, lng: Double): Result<Unit> = runCatching {
        val r = api.setOnline(OnlineRequest(online, lat, lng))
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't go online"))
    }

    suspend fun sendLocation(lat: Double, lng: Double, heading: Double = 0.0, speed: Double = 0.0): Result<Unit> = runCatching {
        val r = api.sendLocation(LocationPing(lat, lng, heading, speed))
        if (!r.isSuccessful) throw IllegalStateException("Location update failed")
    }

    // ── Deliveries ──────────────────────────────────────────

    suspend fun listAvailable(): Result<List<AvailableDelivery>> = runCatching {
        val r = api.listAvailable()
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't load available deliveries"))
        r.body()?.deliveries ?: emptyList()
    }

    suspend fun listUpcoming(): Result<List<AvailableDelivery>> = runCatching {
        val r = api.listUpcoming()
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't load upcoming deliveries"))
        r.body()?.deliveries ?: emptyList()
    }

    suspend fun listActive(): Result<List<CourierOrder>> = runCatching {
        val r = api.listActive()
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't load active deliveries"))
        r.body()?.orders ?: emptyList()
    }

    suspend fun listHistory(): Result<List<HistoryOrder>> = runCatching {
        val r = api.listHistory()
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't load delivery history"))
        r.body()?.orders ?: emptyList()
    }

    suspend fun claim(orderId: String): Result<Unit> = runCatching {
        val r = api.claim(orderId)
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't claim order"))
    }

    suspend fun pickup(orderId: String): Result<Unit> = runCatching {
        val r = api.pickup(orderId)
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't mark pickup"))
    }

    suspend fun deliver(orderId: String): Result<Unit> = runCatching {
        val r = api.deliver(orderId)
        if (!r.isSuccessful) throw IllegalStateException(errorMessage(r, "Couldn't mark delivered"))
    }

    // ── Payouts ─────────────────────────────────────────────

    suspend fun createPayoutAccount(): Result<PayoutStatus> = runCatching {
        val r = api.createPayoutAccount()
        r.body() ?: throw IllegalStateException(errorMessage(r, "Couldn't create Stripe account"))
    }

    suspend fun getPayoutLink(): Result<String> = runCatching {
        val r = api.getPayoutLink()
        r.body()?.url ?: throw IllegalStateException(errorMessage(r, "Couldn't get onboarding link"))
    }

    suspend fun getPayoutStatus(): Result<PayoutStatus> = runCatching {
        val r = api.getPayoutStatus()
        r.body() ?: PayoutStatus(payoutReady = false)
    }

    // ── Uploads ─────────────────────────────────────────────

    suspend fun presignUpload(kind: String, contentType: String): Result<PresignResponse> = runCatching {
        val r = api.presignUpload(PresignRequest(kind, contentType))
        r.body() ?: throw IllegalStateException(errorMessage(r, "Couldn't start upload"))
    }

    // ── Device token ────────────────────────────────────────

    suspend fun registerDevice(token: String): Result<Unit> = runCatching {
        val r = api.registerDevice(RegisterDeviceRequest(token = token))
        if (!r.isSuccessful) throw IllegalStateException("Device registration failed")
    }
}
