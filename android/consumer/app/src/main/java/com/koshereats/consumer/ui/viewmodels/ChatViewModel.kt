package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.ChatMessage
import com.koshereats.consumer.data.models.SendChatMessageRequest
import com.koshereats.consumer.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val scrollToBottom: Boolean = false,
    /** IDs of optimistically-inserted messages that failed to send. */
    val failedMessageIds: Set<String> = emptySet(),
    /** IDs of messages currently in-flight (send or retry). */
    val inFlightMessageIds: Set<String> = emptySet(),
    /** True after a 401/403/404 — polling must not restart. */
    val terminalError: Boolean = false,
)

/**
 * Replace the optimistic [clientId] entry with the server's copy, deduping
 * against the poll. If the 3s poll already merged in the server echo (under
 * [serverMsg].id) while the POST was in flight, mapping clientId -> serverMsg
 * would create two entries with the same id and crash the LazyColumn's
 * `item(key = message.id)`. In that case just drop the optimistic entry.
 */
private fun ChatUiState.reconcileSent(clientId: String, serverMsg: ChatMessage): List<ChatMessage> =
    if (messages.any { it.id == serverMsg.id }) {
        messages.filterNot { it.id == clientId }
    } else {
        messages.map { if (it.id == clientId) serverMsg else it }
    }

/**
 * Polling-based chat mirror of iOS OrderChatView. Starts a 3s refresh loop
 * when the screen opens and cancels it when the ViewModel is cleared.
 *
 * Polling (vs. websockets) matches iOS and the way UberEats / DoorDash
 * actually run their chat — fine for MVP latency, zero extra infra.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle,
    sessionManager: SessionManager,
) : ViewModel() {

    // Route param extracted from the nav graph.
    val orderId: String = savedStateHandle.get<String>("orderId") ?: ""

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        viewModelScope.launch {
            sessionManager.logoutEvent.collect {
                pollJob?.cancel()
                _state.value = ChatUiState()
            }
        }
        fetch()
        // Check terminal status once on load as a safety net — ongoing
        // terminal detection is handled by FCM / SSE push, not polling.
        viewModelScope.launch {
            if (checkOrderTerminal()) return@launch
            startPolling()
        }
    }

    fun updateInput(text: String) = _state.update { it.copy(input = text) }

    fun send() {
        // Cap defensively in case the input field's limit was bypassed (e.g. paste).
        val text = _state.value.input.trim().take(2000)
        if (text.isEmpty() || _state.value.isSending) return

        val clientId = UUID.randomUUID().toString()
        val optimistic = ChatMessage(
            id = clientId,
            orderId = orderId,
            senderRole = "consumer",
            text = text,
            createdAt = java.time.Instant.now().toString(),
        )
        _state.update {
            it.copy(
                messages = it.messages + optimistic,
                input = "",
                isSending = true,
                scrollToBottom = true,
            )
        }

        viewModelScope.launch {
            try {
                val response = withTimeoutOrNull(30_000L) {
                    apiService.sendChatMessage(orderId, SendChatMessageRequest(text))
                }
                val serverMsg = response?.body()
                if (serverMsg != null && response.isSuccessful) {
                    _state.update { current ->
                        current.copy(
                            messages = current.reconcileSent(clientId, serverMsg),
                            isSending = false,
                            error = null,
                        )
                    }
                } else {
                    _state.update { current ->
                        current.copy(
                            isSending = false,
                            failedMessageIds = current.failedMessageIds + clientId,
                            error = if (response == null) "Message timed out — tap to retry"
                                    else "Couldn't send message",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { current ->
                    current.copy(
                        isSending = false,
                        failedMessageIds = current.failedMessageIds + clientId,
                        error = "Network error. Please check your connection.",
                    )
                }
            }
        }
    }

    fun retrySend(clientId: String) {
        val msg = _state.value.messages.find { it.id == clientId } ?: return
        if (clientId in _state.value.inFlightMessageIds) return   // already retrying
        val text = msg.text
        // Keep the same clientId so the message stays in its original chronological
        // position instead of jumping to the tail as a newly-appended message would.
        // Use per-message inFlightMessageIds instead of the global isSending flag so
        // a retry does not block the user from sending new messages concurrently.
        _state.update { current ->
            current.copy(
                failedMessageIds = current.failedMessageIds - clientId,
                inFlightMessageIds = current.inFlightMessageIds + clientId,
                error = null,
            )
        }
        viewModelScope.launch {
            try {
                val response = withTimeoutOrNull(30_000L) {
                    apiService.sendChatMessage(orderId, SendChatMessageRequest(text))
                }
                val serverMsg = response?.body()
                if (serverMsg != null && response.isSuccessful) {
                    _state.update { current ->
                        current.copy(
                            messages = current.reconcileSent(clientId, serverMsg),
                            inFlightMessageIds = current.inFlightMessageIds - clientId,
                            error = null,
                        )
                    }
                } else {
                    _state.update { current ->
                        current.copy(
                            inFlightMessageIds = current.inFlightMessageIds - clientId,
                            failedMessageIds = current.failedMessageIds + clientId,
                            error = if (response == null) "Message timed out — tap to retry"
                                    else "Couldn't send message",
                        )
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { current ->
                    current.copy(
                        inFlightMessageIds = current.inFlightMessageIds - clientId,
                        failedMessageIds = current.failedMessageIds + clientId,
                        error = "Network error. Please check your connection.",
                    )
                }
            }
        }
    }

    private fun fetch() {
        viewModelScope.launch {
            try {
                val response = apiService.listChatMessages(orderId)
                if (response.isSuccessful) {
                    val serverMessages = response.body().orEmpty()
                    _state.update { current ->
                        // Merge by ID so optimistically-added messages survive until the
                        // server echoes them back. Server copy wins for any shared ID.
                        val merged = (current.messages.associateBy { it.id } + serverMessages.associateBy { it.id })
                            .values
                            .sortedWith(compareBy(
                                { it.createdAt.ifEmpty { "9999-99-99T99:99:99Z" } },
                                { it.id },
                            ))
                        current.copy(messages = merged, error = null, scrollToBottom = false)
                    }
                } else {
                    if (response.code() in listOf(401, 403, 404)) {
                        pollJob?.cancel()
                        _state.update { it.copy(terminalError = true, error = "Couldn't refresh chat") }
                    } else {
                        _state.update { it.copy(error = "Couldn't refresh chat") }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = "Network error. Please check your connection.") }
            }
        }
    }

    fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    fun resume() {
        if (_state.value.terminalError) return
        if (pollJob == null || pollJob?.isActive != true) {
            fetch()
            startPolling()
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(3_000)
                fetch()
            }
        }
    }

    /**
     * Returns true when the order's status is terminal (delivered/completed/cancelled).
     * Swallows errors so a transient failure doesn't kill the poll loop.
     */
    private suspend fun checkOrderTerminal(): Boolean = try {
        val resp = apiService.getOrder(orderId)
        if (resp.isSuccessful) {
            val status = resp.body()?.status
            status != null && !status.isActive
        } else false
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        false
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
