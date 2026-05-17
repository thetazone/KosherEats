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
)

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
        startPolling()
    }

    fun updateInput(text: String) = _state.update { it.copy(input = text) }

    fun send() {
        val text = _state.value.input.trim()
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
                            messages = current.messages.map { if (it.id == clientId) serverMsg else it },
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
                _state.update { current ->
                    current.copy(
                        isSending = false,
                        failedMessageIds = current.failedMessageIds + clientId,
                        error = e.localizedMessage ?: "Network error",
                    )
                }
            }
        }
    }

    fun retrySend(clientId: String) {
        val msg = _state.value.messages.find { it.id == clientId } ?: return
        _state.update { current ->
            current.copy(
                messages = current.messages.filter { it.id != clientId },
                failedMessageIds = current.failedMessageIds - clientId,
                input = msg.text,
                error = null,
            )
        }
        send()
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
                            .sortedBy { msg ->
                                // Empty createdAt (e.g. server hasn't stamped yet) goes to
                                // the tail instead of the head of the conversation.
                                msg.createdAt.ifEmpty { "9999-99-99T99:99:99Z" }
                            }
                        current.copy(messages = merged, error = null, scrollToBottom = false)
                    }
                } else {
                    if (response.code() in listOf(401, 403, 404)) {
                        pollJob?.cancel()
                    }
                    _state.update { it.copy(error = "Couldn't refresh chat") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.localizedMessage ?: "Network error") }
            }
        }
    }

    fun pause() {
        pollJob?.cancel()
        pollJob = null
    }

    fun resume() {
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

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
