package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.models.AcceptAgreementRequest
import com.koshereats.seller.data.models.SellerAgreement
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

enum class AgreementGateStatus {
    /** Not checked yet for this session — NavGraph keeps the splash up. */
    UNKNOWN,
    NOT_REQUIRED,
    REQUIRED,
}

data class AgreementGateState(
    val status: AgreementGateStatus = AgreementGateStatus.UNKNOWN,
    val agreement: SellerAgreement? = null,
    val legalName: String = "",
    val agreed: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** Shown after a 409: the terms changed under the seller and were re-fetched. */
    val notice: String? = null,
)

/**
 * Restaurant Partner Agreement gate. Activity-scoped (obtained in NavGraph) so
 * the check survives navigation. NavGraph calls [check] with a key built from
 * the login + active-restaurant state; each distinct key triggers one GET
 * /seller/agreement. Grandfathered restaurants get required=false and never
 * see the gate.
 *
 * Fails OPEN on network errors / 404 / 5xx: the agreement is also enforced
 * server-side where it matters, and a flaky network (or a server that hasn't
 * shipped the endpoint yet) must not lock a restaurant out of live orders.
 * The next key change (login, restaurant switch, onboarding finish) re-checks.
 */
@HiltViewModel
class AgreementViewModel @Inject constructor(
    private val apiService: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow(AgreementGateState())
    val state: StateFlow<AgreementGateState> = _state.asStateFlow()

    private var lastKey: String? = null
    private var generation = 0
    /** Bumped on [reset] so a check still in flight at logout can't leak into the next session. */
    private var session = 0

    fun check(key: String) {
        if (key == lastKey) return
        lastKey = key
        val gen = ++generation
        val sess = session
        viewModelScope.launch {
            val result = fetch()
            if (sess != session) return@launch
            // Apply when this is still the newest check, or when nothing has been
            // decided yet (the first answer is what lifts the splash; a newer
            // in-flight check will still overwrite it when it lands).
            if (gen != generation && _state.value.status != AgreementGateStatus.UNKNOWN) return@launch
            applyFetched(result)
        }
    }

    /**
     * No restaurant yet (onboarding): nothing to gate on. Clears the key so the
     * first real [check] after the restaurant is created is never deduplicated away.
     */
    fun skip() {
        lastKey = null
        generation++
        _state.update { it.copy(status = AgreementGateStatus.NOT_REQUIRED) }
    }

    /** Logout: forget everything so the next login re-checks from scratch. */
    fun reset() {
        lastKey = null
        generation++
        session++
        _state.value = AgreementGateState()
    }

    fun updateLegalName(value: String) {
        _state.update { it.copy(legalName = value.take(MAX_LEGAL_NAME), error = null) }
    }

    fun setAgreed(value: Boolean) {
        _state.update { it.copy(agreed = value, error = null) }
    }

    fun accept() {
        val s = _state.value
        val agreement = s.agreement ?: return
        if (s.isSubmitting) return
        val legalName = s.legalName.trim()
        if (legalName.length < 2) {
            _state.update { it.copy(error = "Enter your business's legal name") }
            return
        }
        if (!s.agreed) {
            _state.update { it.copy(error = "Check the box to agree to the Restaurant Partner Agreement") }
            return
        }
        _state.update { it.copy(isSubmitting = true, error = null, notice = null) }
        viewModelScope.launch {
            try {
                val r = apiService.acceptAgreement(
                    AcceptAgreementRequest(legalName = legalName, version = agreement.currentVersion),
                )
                val body = r.body()
                when {
                    r.isSuccessful && body != null -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                agreement = body,
                                status = if (body.needsAcceptance) AgreementGateStatus.REQUIRED else AgreementGateStatus.NOT_REQUIRED,
                            )
                        }
                    }
                    r.isSuccessful -> {
                        // 2xx with an empty body: the accept landed; treat as done.
                        _state.update { it.copy(isSubmitting = false, status = AgreementGateStatus.NOT_REQUIRED) }
                    }
                    r.code() == 409 -> {
                        // Version moved under us — re-fetch the current terms and ask
                        // the seller to review + agree again.
                        val refreshed = fetch()
                        applyFetched(refreshed)
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                agreed = false,
                                notice = if (it.status == AgreementGateStatus.REQUIRED) {
                                    "The agreement was just updated. Please review the latest version and accept again."
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    else -> {
                        _state.update {
                            it.copy(
                                isSubmitting = false,
                                error = when (r.code()) {
                                    400, 422 -> "Please check the legal business name and try again"
                                    in 500..599 -> "Server error — please try again"
                                    else -> "Couldn't record your acceptance (HTTP ${r.code()})"
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = when (e) {
                            is java.net.UnknownHostException -> "No internet connection"
                            is java.io.IOException -> "Network error — please try again"
                            else -> e.localizedMessage ?: "Something went wrong"
                        },
                    )
                }
            }
        }
    }

    private sealed class Fetched {
        data class Ok(val agreement: SellerAgreement) : Fetched()
        object NotAvailable : Fetched()
    }

    private suspend fun fetch(): Fetched = try {
        val r = withTimeoutOrNull(CHECK_TIMEOUT_MS) { apiService.getAgreement() }
        val body = r?.body()
        if (r != null && r.isSuccessful && body != null) {
            Fetched.Ok(body)
        } else {
            if (r != null) android.util.Log.w(TAG, "GET seller/agreement -> HTTP ${r.code()}; not gating")
            else android.util.Log.w(TAG, "GET seller/agreement timed out; not gating")
            Fetched.NotAvailable
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "GET seller/agreement failed; not gating", e)
        Fetched.NotAvailable
    }

    private fun applyFetched(result: Fetched) {
        when (result) {
            is Fetched.Ok -> _state.update {
                it.copy(
                    agreement = result.agreement,
                    status = if (result.agreement.needsAcceptance) AgreementGateStatus.REQUIRED else AgreementGateStatus.NOT_REQUIRED,
                )
            }
            Fetched.NotAvailable -> _state.update {
                // Keep an already-showing gate up on a transient failure; only an
                // undecided state falls open.
                if (it.status == AgreementGateStatus.UNKNOWN) it.copy(status = AgreementGateStatus.NOT_REQUIRED) else it
            }
        }
    }

    private companion object {
        const val TAG = "AgreementGate"
        const val CHECK_TIMEOUT_MS = 10_000L
        const val MAX_LEGAL_NAME = 200
    }
}
