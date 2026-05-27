package com.greeneats.consumer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.greeneats.consumer.ui.theme.SurfaceDark
import com.greeneats.consumer.ui.theme.SurfaceDarkElevated

/**
 * Returns a shimmer [Brush] suitable for use in [Modifier.drawBehind].
 * The animation runs on the render thread via [graphicsLayer] on each
 * shimmer placeholder, keeping the main-thread cost near zero.
 */
@Composable
fun ShimmerBrush(
    targetValue: Float = 1000f,
    showShimmer: Boolean = true
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            SurfaceDark.copy(alpha = 0.6f),
            SurfaceDarkElevated.copy(alpha = 1.0f),
            SurfaceDark.copy(alpha = 0.6f),
        )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnimation = transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = LinearEasing), repeatMode = RepeatMode.Restart
            ),
            label = "shimmer"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnimation.value, y = translateAnimation.value)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent),
            start = Offset.Zero,
            end = Offset.Zero
        )
    }
}

/**
 * Modifier that draws the shimmer gradient behind the content on the GPU
 * render thread via [graphicsLayer] + [drawBehind], avoiding main-thread
 * recomposition on every animation frame.
 */
@Composable
private fun Modifier.shimmerBackground(): Modifier {
    val brush = ShimmerBrush()
    return this
        .graphicsLayer { /* promotes to a hardware layer for GPU compositing */ }
        .drawBehind { drawRect(brush) }
}

@Composable
fun RestaurantCardShimmer() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            // Hide entire shimmer tree from screen readers
            .clearAndSetSemantics { contentDescription = "Loading restaurant" }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .shimmerBackground()
        )
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerBackground()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerBackground()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(60.dp, 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .shimmerBackground()
                )
                Box(
                    modifier = Modifier
                        .size(60.dp, 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .shimmerBackground()
                )
            }
        }
    }
}

@Composable
fun MenuItemShimmer() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceDark)
            .padding(12.dp)
            // Hide entire shimmer tree from screen readers
            .clearAndSetSemantics { contentDescription = "Loading menu item" },
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerBackground()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerBackground()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .size(60.dp, 20.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerBackground()
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(10.dp))
                .shimmerBackground()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101214)
@Composable
fun ShimmerPreview() {
    Column(modifier = Modifier.fillMaxSize()) {
        RestaurantCardShimmer()
        Spacer(modifier = Modifier.height(16.dp))
        MenuItemShimmer()
    }
}
