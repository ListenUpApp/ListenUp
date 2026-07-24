package com.calypsan.listenup.client.domain.model

/**
 * Domain model for a global (cross-user) mood.
 *
 * Moods are the affective axis of a book ("Feel-Good", "Tense", "Scary"), applied
 * to books by curators — independent of genre and tag.
 *
 * [slug] is the canonical URL-safe identity derived from [name] at creation time
 * (e.g. `"feel-good"` from `"Feel-Good"`). The slug is immutable — renames update
 * [name] but never [slug], so deep links remain stable.
 *
 * Use [displayName] to derive the legacy title-case representation from [slug]
 * when backward compatibility is required; prefer [name] for all new UI code.
 */
data class Mood(
    val id: String,
    /** Human-readable display name as stored server-side, e.g. "Feel-Good". */
    val name: String,
    /** URL-safe slug derived from [name] at creation time, e.g. "feel-good". Immutable. */
    val slug: String,
) {
    /**
     * Derives a title-case human-readable display name from [slug].
     *
     * Transformation: `"feel-good"` → `"Feel Good"`.
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
