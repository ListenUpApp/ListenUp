package com.calypsan.listenup.server.metadata.audible

/**
 * Strips any inner HTML markup and decodes the common HTML entities Audible
 * emits in scraped text — contributor names, product descriptions, and topic-tag
 * labels alike.
 *
 * Used by the Audible product-tag scraper ([parseProductTags]) so entity handling
 * stays consistent across the package.
 */
internal fun stripHtmlEntities(raw: String): String =
    raw
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .trim()
