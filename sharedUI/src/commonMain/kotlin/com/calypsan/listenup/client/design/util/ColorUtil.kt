package com.calypsan.listenup.client.design.util

import androidx.compose.ui.graphics.Color

/**
 * Parses a hex color string (e.g., "#FF6B7280", "#6B7280", "FF6B7280") into a Compose Color.
 * Falls back to gray if the string is invalid.
 */
@Suppress("MagicNumber")
fun parseHexColor(hex: String): Color =
    try {
        val cleaned = hex.removePrefix("#")
        val colorLong =
            when (cleaned.length) {
                6 -> cleaned.toLong(16) or 0xFF000000L
                8 -> cleaned.toLong(16)
                else -> 0xFF6B7280L
            }
        Color(colorLong.toInt())
    } catch (_: Exception) {
        Color(0xFF6B7280.toInt()) // Fallback gray
    }
