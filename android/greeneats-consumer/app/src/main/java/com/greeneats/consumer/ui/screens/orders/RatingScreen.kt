package com.greeneats.consumer.ui.screens.orders

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.greeneats.consumer.ui.theme.*
import com.greeneats.consumer.ui.viewmodels.RatingViewModel

private object RatingStrings {
    const val TITLE = "Rate your courier"
    const val HEADLINE = "How was your delivery?"
    const val COMMENT_LABEL = "Comment (optional)"
    const val COMMENT_PLACEHOLDER = "Tell the courier what they did well"
    const val SUBMITTING = "Submitting..."
    const val SUBMIT = "Submit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RatingScreen(
    orderId: String,
    onBack: () -> Unit,
    vm: RatingViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.submitted) {
        if (state.submitted) onBack()
    }

    Column(Modifier.fillMaxSize().background(BackgroundBlack)) {
        TopAppBar(
            title = { Text(RatingStrings.TITLE, color = TextWhite) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = TextWhite)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundBlack),
        )

        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                RatingStrings.HEADLINE,
                style = MaterialTheme.typography.headlineSmall,
                color = TextWhite,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { i ->
                    Icon(
                        imageVector = if (i <= state.stars) Icons.Filled.Star else Icons.Filled.StarOutline,
                        contentDescription = "$i stars",
                        tint = Orange,
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { vm.setStars(i) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = state.comment,
                onValueChange = vm::setComment,
                label = { Text(RatingStrings.COMMENT_LABEL, color = TextTertiary) },
                placeholder = { Text(RatingStrings.COMMENT_PLACEHOLDER, color = TextMuted) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextWhite,
                    unfocusedTextColor = TextWhite,
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = SurfaceDarkBorder,
                    cursorColor = Orange,
                ),
                shape = RoundedCornerShape(12.dp),
                minLines = 3,
            )

            state.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Text(msg, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.weight(1f))
            Button(
                onClick = { vm.submit(orderId) },
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(
                    if (state.isSubmitting) RatingStrings.SUBMITTING else RatingStrings.SUBMIT,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
