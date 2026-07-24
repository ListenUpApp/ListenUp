package com.calypsan.listenup.client.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android implementation using SimpleDateFormat for locale-aware formatting.
 */
actual fun formatDate(
    epochMillis: Long,
    pattern: String,
): String {
    val formatter = SimpleDateFormat(pattern, Locale.getDefault())
    return formatter.format(Date(epochMillis))
}
