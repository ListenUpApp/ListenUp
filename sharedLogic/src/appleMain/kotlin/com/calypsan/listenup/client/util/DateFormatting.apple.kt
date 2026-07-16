package com.calypsan.listenup.client.util

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * Cache of [NSDateFormatter] instances keyed by pattern.
 *
 * `NSDateFormatter` initialization is one of Apple's documented performance footguns — Cupertino's
 * guidance is to reuse a formatter rather than allocate one per call. Patterns are a tiny fixed set
 * (`"MMMM d, yyyy"`, `"MMMM yyyy"`), so we memoize one formatter per pattern.
 *
 * Access is guarded because `formatDate` is called from both coroutines and Foundation delegate
 * queues; the lock is uncontended in practice and only protects the map mutation.
 */
private object DateFormatterCache : SynchronizedObject() {
    private val formatters = mutableMapOf<String, NSDateFormatter>()

    fun formatter(pattern: String): NSDateFormatter =
        synchronized(this) {
            formatters.getOrPut(pattern) {
                NSDateFormatter().apply {
                    dateFormat = pattern
                    locale = NSLocale.currentLocale
                }
            }
        }
}

/**
 * iOS implementation using a cached [NSDateFormatter] for locale-aware formatting.
 */
actual fun formatDate(
    epochMillis: Long,
    pattern: String,
): String {
    // NSDate uses seconds since 1970, not milliseconds
    val date = NSDate.dateWithTimeIntervalSince1970(epochMillis / 1000.0)
    return DateFormatterCache.formatter(pattern).stringFromDate(date)
}
