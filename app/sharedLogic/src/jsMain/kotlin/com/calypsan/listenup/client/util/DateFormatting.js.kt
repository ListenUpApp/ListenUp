package com.calypsan.listenup.client.util

import kotlin.js.Date
import kotlin.js.dateLocaleOptions

/**
 * Browser date formatting via `toLocaleDateString`.
 *
 * The `expect` takes a Java-style pattern, which `Intl.DateTimeFormat` does not accept — it takes
 * component options instead. Rather than build a pattern translator nobody asked for, this maps
 * the two patterns the codebase actually uses and falls back to a full date for anything else.
 * If a third pattern ever appears it will quietly format as a full date; that is a known and
 * accepted limitation of the seam-check stub.
 */
actual fun formatDate(
    epochMillis: Long,
    pattern: String,
): String {
    val date = Date(epochMillis.toDouble())
    val options =
        if (pattern == "MMMM yyyy") {
            dateLocaleOptions {
                month = "long"
                year = "numeric"
            }
        } else {
            dateLocaleOptions {
                month = "long"
                day = "numeric"
                year = "numeric"
            }
        }
    return date.toLocaleDateString("default", options)
}
