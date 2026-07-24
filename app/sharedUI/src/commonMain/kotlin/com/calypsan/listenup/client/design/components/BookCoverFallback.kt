package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deterministic gradient pairs (top, bottom, on-color), echoing the design's cover palette. */
private val fallbackGradients: List<Triple<Color, Color, Color>> =
    listOf(
        Triple(Color(0xFF6A1B2A), Color(0xFF2A0A12), Color(0xFFFF9E7A)),
        Triple(Color(0xFF13414E), Color(0xFF08222B), Color(0xFF86D6FF)),
        Triple(Color(0xFF3A2B5E), Color(0xFF160F2A), Color(0xFFC9B4FF)),
        Triple(Color(0xFF5B4400), Color(0xFF241A00), Color(0xFFFFD86A)),
        Triple(Color(0xFF0E3B2E), Color(0xFF06201A), Color(0xFF86F0C0)),
        Triple(Color(0xFF5A2516), Color(0xFF240D07), Color(0xFFFFB089)),
        Triple(Color(0xFF1E2A5E), Color(0xFF0B1026), Color(0xFF9FB4FF)),
        Triple(Color(0xFF2A2118), Color(0xFF4A3A28), Color(0xFFF3E6D2)),
    )

/**
 * The canonical "no cover yet" placeholder — a deterministic gradient with the book's title and
 * author, used wherever a cover image is unavailable or not downloaded. Scales its type with size and
 * always renders square (1:1), matching audiobook cover art.
 */
@Composable
fun BookCoverFallback(
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    seed: String = title,
) {
    val palette = remember(seed) { fallbackGradients[seed.hashCode().mod(fallbackGradients.size)] }
    val (top, bottom, ink) = palette
    BoxWithConstraints(
        modifier =
            modifier
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(top, bottom))),
        contentAlignment = Alignment.Center,
    ) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(side * 0.06f)
                    .border(maxOf(1.dp, side * 0.011f), ink.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
        )
        Column(
            modifier = Modifier.padding(horizontal = side * 0.14f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                color = ink,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (side.value * 0.135f).sp,
                lineHeight = (side.value * 0.14f).sp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (author.isNotBlank()) {
                Box(
                    modifier =
                        Modifier
                            .padding(vertical = side * 0.045f)
                            .size(width = side * 0.16f, height = 2.dp)
                            .background(ink.copy(alpha = 0.45f)),
                )
                Text(
                    text = author.uppercase(),
                    color = ink.copy(alpha = 0.78f),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (side.value * 0.052f).sp,
                    letterSpacing = 1.2.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
