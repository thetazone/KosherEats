package com.koshereats.consumer.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.consumer.data.api.ApiService
import com.koshereats.consumer.data.models.ChatMessage
import com.koshereats.consumer.data.models.SendChatMessageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isSending: Boolean = false,
    val error: String? = null,
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
) : ViewModel() {

    // Route param extracted from the nav graph.
    val orderId: String = savedStateHandle.get<String>("orderId") ?: ""

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
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
                    // Don't blow away the list on transient failures; only
                    // overwrite on a successful response.
                    _state.update { it.copy(messages = response.body().orEmpty(), error = null) }
                } else {
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
            while (true) {
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
