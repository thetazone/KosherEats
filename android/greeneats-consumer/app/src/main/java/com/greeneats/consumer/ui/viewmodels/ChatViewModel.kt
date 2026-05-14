package com.greeneats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.greeneats.consumer.data.api.ApiService
import com.greeneats.consumer.data.models.ChatMessage
import com.greeneats.consumer.data.models.SendChatMessageRequest
import com.greeneats.consumer.data.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
    val scrollToBottom: Boolean = false,
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
        viewModelScope.launch {
            _state.update { it.copy(isSending = true) }
            try {
                val response = apiService.sendChatMessage(orderId, SendChatMessageRequest(text))
                val newMessage = response.body()
                if (response.isSuccessful && newMessage != null) {
                    _state.update {
                        it.copy(
                            messages = it.messages + newMessage,
                            input = "",
                            isSending = false,
                            error = null,
                            scrollToBottom = true,
                        )
                    }
                } else {
                    _state.update { it.copy(isSending = false, error = "Couldn't send message") }
                }
            } catch (e: Exception) {
                _state.update { it.copy(isSending = false, error = e.localizedMessage ?: "Network error") }
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
                            .sortedBy { it.createdAt }
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
