package com.calypsan.listenup.client.util

/**
 * Format epoch milliseconds to a localized date string.
 *
 * Platform implementations use native formatters for proper localization.
 *
 * @param epochMillis Unix timestamp in milliseconds
 * @param pattern Date format pattern (e.g., "MMMM d, yyyy" for "January 15, 2024")
 * @return Formatted date string
 */
expect fun formatDate(
    epochMillis: Long,
    pattern: String,
): String

/**
 * Format epoch milliseconds to a short date (e.g., "Jan 15, 2024").
 */
fun formatDateShort(epochMillis: Long): String = formatDate(epochMillis, "MMM d, yyyy")

/**
 * Format epoch milliseconds to a long date (e.g., "January 15, 2024").
 */
fun formatDateLong(epochMillis: Long): String = formatDate(epochMillis, "MMMM d, yyyy")
