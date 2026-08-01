package com.calypsan.listenup.client.data.repository.common

/**
 * Utilities for search query handling.
 *
 * Provides consistent query sanitization and FTS5 formatting
 * across all repositories that perform search operations.
 */
internal object QueryUtils {
    /** Maximum allowed query length. */
    const val MAX_QUERY_LENGTH = 100

    /**
     * Sanitize a user-provided search query.
     *
     * - Trims whitespace
     * - Limits length to prevent abuse
     *
     * Unlike an earlier version of this function, no characters are stripped here.
     * FTS5 special characters (quotes, asterisks, parens, colons, punctuation like
     * "." or "-") are made safe by [toFtsQuery]'s per-token quoting, not by removing
     * them — stripping a "." out of "R.R." or a "'" out of "O'Brien" would corrupt
     * the name the user is searching for.
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
            .take(maxLength)

    /**
     * Convert a user query to FTS5 prefix-match syntax.
     *
     * Each whitespace-separated token becomes a double-quoted FTS5 string literal
     * with a trailing asterisk for prefix matching: "brandon sanderson" ->
     * `"brandon"* "sanderson"*`. Multiple quoted terms are ANDed together implicitly,
     * preserving the existing multi-token match semantics.
     *
     * Quoting every token — rather than emitting it as a bareword — is what makes
     * this safe for punctuated names. An FTS5 bareword may contain only ASCII
     * alphanumerics, `_`, and codepoints above 127; a "." or "-" or "'" in a bareword
     * aborts the query parser. Inside a quoted string those characters are just
     * literal text, so "George R.R. Martin" becomes `"George"* "R.R."* "Martin"*`
     * instead of failing with a syntax error. A literal `"` in the input is doubled
     * per FTS5's escaping rule so the quoted string stays well-formed; a literal `*`
     * typed by the user lands inside the quotes too, so it is matched as ordinary
     * text rather than acting as a wildcard.
     *
     * @param query Sanitized query (should call [sanitize] first)
     * @return FTS5-formatted query with prefix matching
     */
    fun toFtsQuery(query: String): String =
        query
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token -> quotePrefixTerm(token) }

    /** Wraps [token] as a double-quoted FTS5 prefix term, doubling any embedded quote. */
    private fun quotePrefixTerm(token: String): String {
        val quote = '"'
        val escaped = token.replace(quote.toString(), "$quote$quote")
        return "$quote$escaped$quote*"
    }

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
internal fun String.sanitizeForSearch(maxLength: Int = QueryUtils.MAX_QUERY_LENGTH): String =
    QueryUtils.sanitize(this, maxLength)

/**
 * Extension function to convert to FTS query.
 */
internal fun String.toFtsQuery(): String = QueryUtils.toFtsQuery(this)
