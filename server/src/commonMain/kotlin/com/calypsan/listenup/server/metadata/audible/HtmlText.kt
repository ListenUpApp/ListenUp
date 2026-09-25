package com.calypsan.listenup.server.metadata.audible

/**
 * Decodes the common HTML entities providers emit in text — contributor names,
 * product descriptions, and topic-tag labels alike. The single entity table both
 * [stripHtmlEntities] and the neutral `providerHtmlToPlainText` converter decode
 * from, so there is one place that knows the mapping.
 */
internal fun decodeHtmlEntities(raw: String): String =
    raw
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")

/**
 * Strips any inner HTML markup and decodes the common HTML entities Audible
 * emits in scraped text — contributor names, product descriptions, and topic-tag
 * labels alike.
 *
 * Used by the Audible product-tag scraper ([parseProductTags]) so entity handling
 * stays consistent across the package.
 */
internal fun stripHtmlEntities(raw: String): String = decodeHtmlEntities(raw.replace(Regex("<[^>]+>"), "")).trim()
