@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.core

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Type-safe wrapper for server URLs with built-in validation and normalization.
 *
 * Validates URL format (must start with http:// or https://) and normalizes
 * by removing the trailing slash. Client-only — the server doesn't need to
 * know which server URL the client is pointed at.
 */
@Serializable
@JvmInline
value class ServerUrl(
    val raw: String,
) {
    init {
        require(raw.isNotBlank()) { "Server URL cannot be blank" }
        require(raw.startsWith("http://") || raw.startsWith("https://")) {
            "Server URL must start with http:// or https://, got: $raw"
        }
    }

    /** Normalized form — trailing slash removed. */
    val value: String get() = raw.trimEnd('/')

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Book IDs.
 *
 * Provides compile-time type safety to prevent accidentally passing wrong ID types
 * (e.g., user IDs, instance IDs) where book IDs are expected.
 *
 * Value class compiles to primitive String with zero runtime overhead while
 * maintaining compile-time type checking.
 *
 * @property value The underlying book ID string (e.g., "book-abc123")
 */
@JvmInline
value class BookId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Book ID cannot be blank" }
    }

    override fun toString(): String = value

    companion object {
        /**
         * Create BookId from string value.
         * Validates that value is not blank.
         */
        fun fromString(value: String): BookId = BookId(value)
    }
}

/**
 * Type-safe wrapper for Chapter IDs.
 */
@JvmInline
value class ChapterId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Chapter ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Series IDs.
 */
@JvmInline
value class SeriesId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Series ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Contributor IDs.
 */
@JvmInline
value class ContributorId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Contributor ID cannot be blank" }
    }

    override fun toString(): String = value
}

/**
 * Type-safe wrapper for Unix epoch millisecond timestamps.
 *
 * Prevents accidentally comparing timestamps with durations or other numeric values.
 * Provides rich API for timestamp operations while compiling to primitive Long
 * with zero runtime overhead.
 *
 * @property epochMillis Unix epoch milliseconds
 */
@JvmInline
value class Timestamp(
    val epochMillis: Long,
) : Comparable<Timestamp> {
    override fun compareTo(other: Timestamp): Int = epochMillis.compareTo(other.epochMillis)

    /**
     * Calculate duration between two timestamps.
     */
    operator fun minus(other: Timestamp): Duration = (epochMillis - other.epochMillis).milliseconds

    /**
     * Add duration to timestamp.
     */
    operator fun plus(duration: Duration): Timestamp = Timestamp(epochMillis + duration.inWholeMilliseconds)

    override fun toString(): String = epochMillis.toString()

    /**
     * Convert to ISO 8601 date time string.
     * e.g. "2023-11-22T14:30:45.123Z"
     */
    fun toIsoString(): String = Instant.fromEpochMilliseconds(epochMillis).toString()

    companion object {
        /**
         * Get current system time as Timestamp.
         */
        fun now(): Timestamp = Timestamp(currentEpochMilliseconds())

        /**
         * Create Timestamp from epoch milliseconds.
         */
        fun fromEpochMillis(value: Long): Timestamp = Timestamp(value)
    }
}
