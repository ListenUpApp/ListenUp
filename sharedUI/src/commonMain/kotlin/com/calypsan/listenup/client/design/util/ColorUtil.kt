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

/**
 * Derives a stable Material color from an arbitrary identifier (e.g. a user id).
 *
 * Used where the backend supplies an owner identity but no avatar color — the same
 * id always maps to the same color, so shelf cards stay visually consistent.
 */
fun stableColorForId(id: String): Color = idColorPalette[id.hashCode().mod(idColorPalette.size)]

/** Twelve-color Material 3 palette mirroring the avatar palette used elsewhere. */
@Suppress("MagicNumber")
private val idColorPalette =
    listOf(
        Color(0xFFE53935L),
        Color(0xFFD81B60L),
        Color(0xFF8E24AAL),
        Color(0xFF5E35B1L),
        Color(0xFF3949ABL),
        Color(0xFF1E88E5L),
        Color(0xFF039BE5L),
        Color(0xFF00ACC1L),
        Color(0xFF00897BL),
        Color(0xFF43A047L),
        Color(0xFFFB8C00L),
        Color(0xFF6D4C41L),
    )
