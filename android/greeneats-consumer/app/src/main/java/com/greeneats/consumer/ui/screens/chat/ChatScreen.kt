package com.greeneats.consumer.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.data.models.ChatMessage
import com.greeneats.consumer.ui.theme.BackgroundBlack
import com.greeneats.consumer.ui.theme.BackgroundDark
import com.greeneats.consumer.ui.theme.ErrorRed
import com.greeneats.consumer.ui.theme.Orange
import com.greeneats.consumer.ui.theme.SurfaceDark
import com.greeneats.consumer.ui.theme.TextMuted
import com.greeneats.consumer.ui.theme.TextSecondary
import com.greeneats.consumer.ui.theme.TextWhite
import com.greeneats.consumer.ui.viewmodels.ChatViewModel

/**
 * Order-scoped chat screen. Mirror of iOS OrderChatView. Polls every 3s
 * (handled by the ViewModel) and auto-scrolls to newest on update.
 *
 * Bubble alignment: consumer-authored messages (role=="consumer") render
 * right-aligned with the primary color; courier + seller messages align
 * left on the card background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    // Track scroll position post-layout so reads are never stale.
    val isScrolledUp = remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { isScrolledUp.value = it }
    }

    // Auto-scroll only when the user is already at the bottom or just sent a message.
    val messageCount by remember { derivedStateOf { state.messages.size } }
    LaunchedEffect(messageCount) {
        if (messageCount > 0 && (!isScrolledUp.value || state.scrollToBottom)) {
            listState.animateScrollToItem(messageCount - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundBlack)
            .imePadding(),
    ) {
        TopAppBar(
            title = { Text("Chat", color = TextWhite, fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        if (state.messages.isEmpty()) {
            EmptyState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
            ) {
                for (message in state.messages) {
                    item(key = message.id) {
                        ChatBubble(message)
                    }
                }
            }
        }

        state.error?.let {
            Text(
                text = it,
                color = ErrorRed,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        InputBar(
            value = state.input,
            isSending = state.isSending,
            onChange = viewModel::updateInput,
            onSend = viewModel::send,
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.ChatBubbleOutline,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text("No messages yet", color = TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "Send a note to your driver or the restaurant.",
                color = TextMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    // Consumer-authored messages belong to the local user.
    val isMine = message.senderRole == "consumer"
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val bubbleColor = if (isMine) Orange else SurfaceDark
    val textColor = if (isMine) Color.White else TextWhite

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (!isMine) {
            Text(
                text = senderLabel(message.senderRole),
                color = Orange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                // Bubbles never span more than 80% of the row. The column
                // alignment above handles left/right positioning.
                .fillMaxWidth(0.8f)
                .wrapContentWidth(if (isMine) Alignment.End else Alignment.Start)
                .clip(RoundedCornerShape(18.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.text, color = textColor, fontSize = 14.sp)
        }
        Text(
            text = shortTime(message.createdAt),
            color = TextMuted,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        )
    }
}

@Composable
private fun InputBar(
    value: String,
    isSending: Boolean,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = value.isNotBlank() && !isSending
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BackgroundDark)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = { if (it.length <= 2000) onChange(it) },
            modifier = Modifier.weight(1f),
            placeholder = { Text("Type a message…", color = TextMuted) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
                focusedTextColor = TextWhite,
                unfocusedTextColor = TextWhite,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Orange,
            ),
            maxLines = 4,
            shape = RoundedCornerShape(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (canSend) Orange else TextMuted),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (isSending) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Color.White)
                }
            }
        }
    }
}

private fun senderLabel(role: String): String = when (role) {
    "courier" -> "Driver"
    "seller" -> "Restaurant"
    "consumer" -> "You"
    else -> role.replaceFirstChar { it.uppercase() }
}

/**
 * Renders the timestamp as a short clock string. Accepts ISO-8601
 * (`2026-04-05T14:03:22Z`) — which is what the backend sends — and falls
 * back to the raw string on parse failure so we never crash on a format
 * tweak.
 */
private fun shortTime(iso: String): String {
    return try {
        val instant = java.time.OffsetDateTime.parse(iso)
        val local = instant.atZoneSameInstant(java.time.ZoneId.systemDefault())
        java.time.format.DateTimeFormatter.ofPattern("h:mm a").format(local)
    } catch (_: Throwable) {
        iso
    }
}

