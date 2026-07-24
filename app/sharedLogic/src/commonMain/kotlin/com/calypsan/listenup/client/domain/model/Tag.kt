package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a global (cross-user) tag.
 *
 * Tags are community-wide content descriptors curators apply to books.
 * Examples: "Sci-Fi", "Found Family", "Slow Burn", "Unreliable Narrator".
 *
 * [slug] is the canonical URL-safe identity derived from [name] at creation time
 * (e.g. `"sci-fi"` from `"Sci-Fi"`). The slug is immutable — renames update [name]
 * but never [slug], so deep links remain stable.
 *
 * Use [displayName] to derive the legacy title-case representation from [slug]
 * when backward compatibility is required; prefer [name] for all new UI code.
 */
data class Tag(
    val id: String,
    /** Human-readable display name as stored server-side, e.g. "Sci-Fi". */
    val name: String,
    /** URL-safe slug derived from [name] at creation time, e.g. "sci-fi". Immutable. */
    val slug: String,
) {
    /**
     * Derives a title-case human-readable display name from [slug].
     *
     * Transformation: `"found-family"` → `"Found Family"`.
     *
     * Prefer [name] for new code; this method is retained for legacy callers that
     * depended on slug-derived display names.
     */
    fun displayName(): String =
        slug
            .split("-")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.titlecase() }
            }
}
