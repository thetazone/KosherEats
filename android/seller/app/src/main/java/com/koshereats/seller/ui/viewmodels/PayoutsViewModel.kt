package com.koshereats.seller.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.seller.data.api.ApiService
import com.koshereats.seller.data.api.NetworkModule
import com.koshereats.seller.data.models.PayoutLine
import com.koshereats.seller.data.models.PayoutStatus
import com.koshereats.seller.data.models.PayoutSummary
import com.koshereats.seller.data.repository.PayoutsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

/** Payouts are reported in the restaurants' market time zone, not the device's. */
val PAYOUTS_ZONE: ZoneId = ZoneId.of("America/New_York")

/**
 * Summary windows. Weeks run Monday–Sunday; "This week" / "This month" end
 * today. Dates are inclusive and computed in America/New_York.
 */
enum class PayoutPeriod(val label: String) {
    THIS_WEEK("This week"),
    LAST_WEEK("Last week"),
    THIS_MONTH("This month");

    fun range(today: LocalDate = LocalDate.now(PAYOUTS_ZONE)): Pair<LocalDate, LocalDate> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when (this) {
            THIS_WEEK -> monday to today
            LAST_WEEK -> monday.minusWeeks(1) to monday.minusDays(1)
            THIS_MONTH -> today.withDayOfMonth(1) to today
        }
    }
}

data class PayoutsUiState(
    val period: PayoutPeriod = PayoutPeriod.THIS_WEEK,
    val summaries: Map<PayoutPeriod, PayoutSummary> = emptyMap(),
    val summaryLoading: Boolean = false,
    val summaryError: String? = null,
    val lines: List<PayoutLine> = emptyList(),
    val nextCursor: String? = null,
    /** True once the first history page has come back (success or failure). */
    val linesLoaded: Boolean = false,
    val linesLoading: Boolean = false,
    val loadingMore: Boolean = false,
    val linesError: String? = null,
    val isRefreshing: Boolean = false,
    val isStartingSetup: Boolean = false,
    /** Set while the Stripe Custom Tab is open, so ON_RESUME knows to re-check. */
    val awaitingStripeReturn: Boolean = false,
    val isCheckingStatus: Boolean = false,
    val setupError: String? = null,
) {
    val summary: PayoutSummary? get() = summaries[period]
}

@HiltViewModel
class PayoutsViewModel @Inject constructor(
    private val apiService: ApiService,
    private val payoutsRepository: PayoutsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PayoutsUiState())
    val state: StateFlow<PayoutsUiState> = _state.asStateFlow()

    val payoutStatus: StateFlow<PayoutStatus?> = payoutsRepository.status

    private var summaryJob: Job? = null
    private var linesJob: Job? = null

    init {
        loadAll()
        viewModelScope.launch {
            NetworkModule.restaurantChanged.collect {
                summaryJob?.cancel()
                linesJob?.cancel()
                _state.value = PayoutsUiState(period = _state.value.period)
                loadAll()
            }
        }
    }

    // Status is not fetched here: the screen's ON_RESUME (which also fires on
    // first entry) refreshes it, and PayoutsRepository re-fetches it itself on a
    // restaurant switch.
    private fun loadAll() {
        loadSummary(_state.value.period, force = true)
        loadFirstPage()
    }

    /** Pull-to-refresh: status, the selected summary window, and history page 1. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, summaries = emptyMap()) }
            val summary = loadSummary(_state.value.period, force = true)
            val lines = loadFirstPage()
            refreshStatusNow()
            summary?.join()
            lines.join()
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch { refreshStatusNow() }
    }

    private suspend fun refreshStatusNow() {
        _state.update { it.copy(isCheckingStatus = true) }
        payoutsRepository.refreshStatus()
        _state.update { it.copy(isCheckingStatus = false) }
    }

    /** ON_RESUME hook: re-check status whenever the seller comes back (e.g. from Stripe). */
    fun onResume() {
        if (_state.value.awaitingStripeReturn) {
            _state.update { it.copy(awaitingStripeReturn = false) }
        }
        refreshStatus()
    }

    fun selectPeriod(period: PayoutPeriod) {
        if (_state.value.period == period) return
        _state.update { it.copy(period = period, summaryError = null) }
        loadSummary(period, force = false)
    }

    private fun loadSummary(period: PayoutPeriod, force: Boolean): Job? {
        if (!force && _state.value.summaries.containsKey(period)) return null
        summaryJob?.cancel()
        return viewModelScope.launch {
            _state.update { it.copy(summaryLoading = true, summaryError = null) }
            val (from, to) = period.range()
            try {
                val r = apiService.getPayoutSummary(from = from.toString(), to = to.toString())
                val body = r.body()
                if (r.isSuccessful && body != null) {
                    _state.update {
                        it.copy(summaries = it.summaries + (period to body), summaryLoading = false)
                    }
                } else {
                    _state.update {
                        it.copy(summaryLoading = false, summaryError = httpError(r.code(), "Couldn't load your earnings summary"))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(summaryLoading = false, summaryError = networkError(e)) }
            }
        }.also { summaryJob = it }
    }

    private fun loadFirstPage(): Job {
        linesJob?.cancel()
        return viewModelScope.launch {
            _state.update { it.copy(linesLoading = true, linesError = null) }
            try {
                val r = apiService.listPayouts(limit = PAGE_SIZE, cursor = null)
                val body = r.body()
                if (r.isSuccessful && body != null) {
                    _state.update {
                        it.copy(
                            lines = body.lines,
                            nextCursor = body.nextCursor?.takeIf { c -> c.isNotBlank() },
                            linesLoaded = true,
                            linesLoading = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            linesLoaded = true,
                            linesLoading = false,
                            linesError = httpError(r.code(), "Couldn't load payout history"),
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(linesLoaded = true, linesLoading = false, linesError = networkError(e)) }
            }
        }.also { linesJob = it }
    }

    /** Next history page; no-op while a page is in flight or when there is none. */
    fun loadMore() {
        val s = _state.value
        val cursor = s.nextCursor ?: return
        if (s.loadingMore || s.linesLoading) return
        _state.update { it.copy(loadingMore = true, linesError = null) }
        linesJob = viewModelScope.launch {
            try {
                val r = apiService.listPayouts(limit = PAGE_SIZE, cursor = cursor)
                val body = r.body()
                if (r.isSuccessful && body != null) {
                    _state.update {
                        // De-dupe by id in case a line shifted pages between requests.
                        val seen = it.lines.mapTo(HashSet()) { l -> l.id }
                        it.copy(
                            lines = it.lines + body.lines.filter { l -> l.id !in seen },
                            nextCursor = body.nextCursor?.takeIf { c -> c.isNotBlank() && c != cursor },
                            loadingMore = false,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(loadingMore = false, linesError = httpError(r.code(), "Couldn't load more payouts"))
                    }
                }
            } catch (e: CancellationException) {
                _state.update { it.copy(loadingMore = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loadingMore = false, linesError = networkError(e)) }
            }
        }
    }

    /**
     * POST /seller/payouts/account → GET /seller/payouts/link. Returns the
     * Stripe URL for the caller to open in a Custom Tab, or null on failure
     * (with [PayoutsUiState.setupError] set).
     */
    suspend fun startSetup(): String? {
        if (_state.value.isStartingSetup) return null
        _state.update { it.copy(isStartingSetup = true, setupError = null) }
        val result = payoutsRepository.startOnboarding()
        _state.update {
            it.copy(
                isStartingSetup = false,
                awaitingStripeReturn = result.isSuccess,
                setupError = result.exceptionOrNull()?.message,
            )
        }
        return result.getOrNull()
    }

    fun clearSetupError() {
        _state.update { it.copy(setupError = null) }
    }

    private fun httpError(code: Int, fallback: String): String = when (code) {
        404 -> "Payouts aren't available yet — check back soon"
        in 500..599 -> "$fallback (server error)"
        else -> "$fallback (HTTP $code)"
    }

    private fun networkError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "The server took too long to respond"
        is java.io.IOException -> "Network error — please try again"
        else -> e.localizedMessage ?: "Something went wrong"
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}

/** Status for the Dashboard prompt card and Settings row (shared repository state). */
@HiltViewModel
class PayoutStatusViewModel @Inject constructor(
    private val payoutsRepository: PayoutsRepository,
) : ViewModel() {
    val status: StateFlow<PayoutStatus?> = payoutsRepository.status

    fun refresh() {
        viewModelScope.launch { payoutsRepository.refreshStatus() }
    }
}
