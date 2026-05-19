package com.calypsan.listenup.client.data.repository.common

/**
 * Utilities for search query handling.
 *
 * Provides consistent query sanitization and FTS5 formatting
 * across all repositories that perform search operations.
 */
object QueryUtils {
    /** Maximum allowed query length. */
    const val MAX_QUERY_LENGTH = 100

    /** Characters that have special meaning in FTS5 queries. */
    private val FTS_SPECIAL_CHARS = Regex("[\"*():]")

    /**
     * Sanitize a user-provided search query.
     *
     * - Trims whitespace
     * - Removes FTS5 special characters to prevent injection
     * - Limits length to prevent abuse
     *
     * @param query Raw user input
     * @param maxLength Maximum allowed length (default 100)
     * @return Sanitized query safe for FTS5
     */
    fun sanitize(
        query: String,
        maxLength: Int = MAX_QUERY_LENGTH,
    ): String =
        query
            .trim()
            .replace(FTS_SPECIAL_CHARS, "")
            .take(maxLength)

    /**
     * Convert a user query to FTS5 prefix-match syntax.
     *
     * Each word gets a trailing asterisk for prefix matching:
     * "brandon sanderson" -> "brandon* sanderson*"
     *
     * This enables "bran" to match "brandon" in search results.
     *
     * @param query Sanitized query (should call [sanitize] first)
     * @return FTS5-formatted query with prefix matching
     */
    fun toFtsQuery(query: String): String =
        query
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { "$it*" }

    /**
     * Convert to FTS5 query with sanitization in one step.
     *
     * Convenience function combining [sanitize] and [toFtsQuery].
     *
     * @param query Raw user input
     * @return Sanitized FTS5-formatted query
     */
    fun toSanitizedFtsQuery(query: String): String = toFtsQuery(sanitize(query))
}

/**
 * Extension function for convenient query sanitization.
 */
fun String.sanitizeForSearch(maxLength: Int = QueryUtils.MAX_QUERY_LENGTH): String =
    QueryUtils.sanitize(this, maxLength)

/**
 * Extension function to convert to FTS query.
 */
fun String.toFtsQuery(): String = QueryUtils.toFtsQuery(this)
