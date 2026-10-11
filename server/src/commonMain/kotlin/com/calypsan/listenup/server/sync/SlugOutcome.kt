package com.calypsan.listenup.server.sync

/** What [slugify] made of a display name. */
internal sealed interface SlugOutcome {
    /** A usable slug. */
    data class Ok(
        val slug: String,
    ) : SlugOutcome

    /** Nothing sluggable was left — blank, or only punctuation. */
    data object Empty : SlugOutcome

    /** The slug is longer than the caller's limit. */
    data object TooLong : SlugOutcome
}

private val NON_SLUG_RUN = Regex("[^a-z0-9]+")

/**
 * A URL-safe slug for [raw]: diacritics folded via [foldDiacritics] (the one platform Unicode
 * step: NFKD on JVM, a Latin folding table on native), lowercased, `&` spelled `" and "` so
 * adjacent words do not run together, every other run of non-alphanumerics collapsed to one `-`,
 * and leading and trailing dashes trimmed. A result longer than [maxLength] is [SlugOutcome.TooLong].
 */
internal fun slugify(
    raw: String,
    maxLength: Int,
): SlugOutcome {
    val slug =
        foldDiacritics(raw)
            .lowercase()
            .replace("&", " and ")
            .replace(NON_SLUG_RUN, "-")
            .trim('-')
    return when {
        slug.isEmpty() -> SlugOutcome.Empty
        slug.length > maxLength -> SlugOutcome.TooLong
        else -> SlugOutcome.Ok(slug)
    }
}
