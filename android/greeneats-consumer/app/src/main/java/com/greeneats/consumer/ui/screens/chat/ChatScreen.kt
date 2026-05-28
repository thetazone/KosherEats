package com.greeneats.consumer.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.data.models.ChatMessage
import com.greeneats.consumer.ui.theme.BackgroundBlack
import com.greeneats.consumer.ui.theme.BackgroundDark
import com.greeneats.consumer.ui.theme.ErrorRed
import com.greeneats.consumer.ui.theme.Orange
import com.greeneats.consumer.ui.theme.SuccessGreen
import com.greeneats.consumer.ui.theme.SurfaceDark
import com.greeneats.consumer.ui.theme.TextMuted
import com.greeneats.consumer.ui.theme.TextSecondary
import com.greeneats.consumer.ui.theme.TextWhite
import com.greeneats.consumer.ui.theme.WarningYellow
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
    val state by viewModel.state.collectAsStateWithLifecycle()
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
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back",
                        tint = TextWhite,
                    )
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
                    .padding(horizontal = 16.dp)
                    .semantics { contentDescription = "Chat messages" },
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

        state.error?.let { errorText ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Error: $errorText"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = ErrorRed,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = errorText,
                    color = ErrorRed,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                if (state.lastFailedText != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Retry",
                        color = Orange,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { viewModel.retryLastMessage() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
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
    val senderName = senderLabel(message.senderRole)
    val timeStr = shortTime(message.createdAt)

    // Message status: if the id is blank the message was added optimistically
    // and hasn't been confirmed by the server yet.
    val isSent = message.id.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "$senderName said: ${message.text}, $timeStr" +
                            if (isMine && !isSent) ", sending" else ""
            },
        horizontalAlignment = alignment,
    ) {
        if (!isMine) {
            Text(
                text = senderName,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
        ) {
            Text(
                text = timeStr,
                color = TextMuted,
                fontSize = 10.sp,
            )
            // Status indicator for own messages
            if (isMine) {
                Spacer(Modifier.width(4.dp))
                if (isSent) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Sent",
                        tint = SuccessGreen,
                        modifier = Modifier.size(12.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = "Sending",
                        tint = WarningYellow,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
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
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (canSend) "Send message" else "Send message (disabled)",
                        tint = Color.White,
                    )
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

