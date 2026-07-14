package com.calypsan.listenup.server.metadata.audible

/**
 * A typed "topic tag" scraped from an Audible product page (`/pd/{ASIN}`).
 *
 * Audible surfaces these as anchors of the form
 * `href="/tag/{type}/{Name}-Audiobooks/adbl_rec_tag_…">{display name}</a>` inside
 * a `product-topictag-impression` container. The `type` segment classifies the
 * tag (`mood`, `theme`, `genre`, `social_media`, `audible_editors`, …); the
 * anchor's inner text is the human-readable [name].
 *
 * Classification into Moods / Tropes happens in [ProductTagClassifier].
 */
data class ProductTag(
    val type: String,
    val name: String,
)

/**
 * Extracts the typed topic tags from an Audible product page HTML body.
 *
 * Matches anchors `href="/tag/{type}/{slug}/adbl_rec_tag_{id}…">{name}</a>`,
 * pulling [ProductTag.type] from the URL's first path segment and
 * [ProductTag.name] from the anchor's inner text. Results are deduplicated by
 * `(type, name)` because the same tag repeats across impression blocks, and
 * preserve first-seen order.
 *
 * **Query-param tolerant**: Audible appends `?ref=…` tracking params after the
 * `adbl_rec_tag_{id}` segment, so the pattern tolerates anything up to the closing
 * quote rather than requiring it immediately after the id. The marker `adbl_rec_tag`
 * distinguishes these recommendation topic tags from ordinary `/tag/…` links on the page.
 *
 * Uses regex extraction rather than a full HTML parser — no non-Kotlin-native dependency.
 */
internal fun parseProductTags(html: String): List<ProductTag> {
    val anchorPattern =
        Regex(
            """href="/tag/([^/]+)/[^"]*?adbl_rec_tag[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    val seen = mutableSetOf<Pair<String, String>>()
    val results = mutableListOf<ProductTag>()

    anchorPattern.findAll(html).forEach { match ->
        val type = match.groupValues[1].trim().takeIf { it.isNotBlank() } ?: return@forEach
        val name = stripHtmlEntities(match.groupValues[2]).takeIf { it.isNotBlank() } ?: return@forEach
        if (seen.add(type to name)) {
            results += ProductTag(type = type, name = name)
        }
    }

    return results
}
