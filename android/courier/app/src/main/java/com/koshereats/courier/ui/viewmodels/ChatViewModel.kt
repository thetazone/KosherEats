package com.koshereats.courier.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.koshereats.courier.data.api.ApiService
import com.koshereats.courier.data.models.ChatMessage
import com.koshereats.courier.data.models.SendChatMessageRequest
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
 * Polling-based chat ViewModel for the courier app. Mirrors the iOS
 * OrderChatView polling pattern — fetches the message list every 3s while
 * the screen is visible and auto-posts new messages via the backend.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val apiService: ApiService,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>("orderId") ?: ""

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    init {
        if (orderId.isNotBlank()) startPolling()
    }

    fun pausePolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun resumePolling() {
        if (orderId.isBlank()) return
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

    private suspend fun fetch() {
        try {
            val response = apiService.listChatMessages(orderId)
            if (response.isSuccessful) {
                val incoming = response.body().orEmpty()
                _state.update {
                    val merged = (it.messages + incoming).distinctBy { m -> m.id }
                    it.copy(messages = merged, error = null)
                }
            } else {
                _state.update { it.copy(error = "Couldn't refresh chat") }
            }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.localizedMessage ?: "Network error") }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (true) {
                fetch()
                delay(3_000)
            }
        }
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
