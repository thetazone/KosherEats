package com.koshereats.seller.data.repository

import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.models.PayoutStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing payout state derived from [PayoutStatus]. */
enum class PayoutSetupState(val label: String) {
    READY("Ready"),
    PENDING_VERIFICATION("Pending verification"),
    NOT_SET_UP("Not set up"),
}

val PayoutStatus.setupState: PayoutSetupState
    get() = when {
        payoutReady -> PayoutSetupState.READY
        detailsSubmitted -> PayoutSetupState.PENDING_VERIFICATION
        else -> PayoutSetupState.NOT_SET_UP
    }

/**
 * Single source of truth for the active restaurant's Stripe Connect payout
 * status, shared by the Dashboard prompt card, the Settings "Payouts" row and
 * the Payouts screen, so finishing Stripe onboarding in a Custom Tab updates
 * all three on return. Cleared + re-fetched when the seller switches restaurant
 * (payout accounts are per restaurant via the restaurant_id interceptor).
 */
@Singleton
class PayoutsRepository @Inject constructor(
    private val api: ApiService,
) {
    private val _status = MutableStateFlow<PayoutStatus?>(null)

    /** null = not loaded yet (or last load failed before anything was known). */
    val status: StateFlow<PayoutStatus?> = _status.asStateFlow()

    init {
        NetworkModule.appScope.launch {
            NetworkModule.restaurantChanged.collect {
                _status.value = null
                refreshStatus()
            }
        }
    }

    /** Logout: drop the previous account's status so the next seller never sees it. */
    fun clear() {
        _status.value = null
    }

    /**
     * Re-reads GET /seller/payouts/status. A failure keeps the last known value
     * so a flaky network never flips a Ready restaurant back to "Not set up".
     */
    suspend fun refreshStatus(): Result<PayoutStatus> = runCatchingCancellable {
        val r = api.getPayoutStatus()
        val body = r.body()
        if (!r.isSuccessful || body == null) error(errorMessage(r, "Couldn't load payout status"))
        _status.value = body
        body
    }

    /**
     * Starts (or resumes) Stripe-hosted onboarding: idempotently creates the
     * Connect account, then fetches a fresh account link. Returns the URL to open
     * in a Custom Tab.
     */
    suspend fun startOnboarding(): Result<String> = runCatchingCancellable {
        val acct = api.createPayoutAccount()
        val acctBody = acct.body()
        if (!acct.isSuccessful) error(errorMessage(acct, "Couldn't create your Stripe account"))
        if (acctBody != null) _status.value = acctBody
        val link = api.getPayoutLink()
        val url = link.body()?.url
        if (!link.isSuccessful || url.isNullOrBlank()) error(errorMessage(link, "Couldn't get the Stripe setup link"))
        url
    }

    private fun errorMessage(r: Response<*>, fallback: String): String = when (r.code()) {
        401, 403 -> "You don't have access to payouts for this restaurant"
        404 -> "Payouts aren't available yet — please try again later"
        in 500..599 -> "$fallback (server error)"
        else -> "$fallback (HTTP ${r.code()})"
    }

    private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        Result.failure(IllegalStateException("Network error — please try again", e))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
