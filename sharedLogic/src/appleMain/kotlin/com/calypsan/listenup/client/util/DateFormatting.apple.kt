package com.calypsan.listenup.client.util

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS implementation using NSDateFormatter for locale-aware formatting.
 */
actual fun formatDate(
    epochMillis: Long,
    pattern: String,
): String {
    val formatter =
        NSDateFormatter().apply {
            dateFormat = pattern
            locale = NSLocale.currentLocale
        }
    // NSDate uses seconds since 1970, not milliseconds
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    return formatter.stringFromDate(date)
}
