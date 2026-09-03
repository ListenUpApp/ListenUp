package com.calypsan.listenup.web.design

// Initials and tint for a person's avatar — shared between the Contributors list and Contributor
// Detail's hero, so a given contributor reads the same monogram and colour on both pages.
//
// Promoted out of `ContributorsPage` (which introduced them first) rather than left as that page's
// private helpers, so a second screen showing the same person's avatar calls the same code instead
// of growing its own copy of the hash math.

/** Up to two initials: the first letter of the first and last name tokens, or just one for a single-word name. */
internal fun initialsFor(name: String): String {
    val parts = name.trim().split(AVATAR_WHITESPACE).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts.first().take(1).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

/**
 * A stable, name-derived tint for a contributor's avatar.
 *
 * Shares [tintGradient] with [Cover]'s fallback for a book with no artwork — the same
 * hash-the-string-into-a-hue trick, tuned darker and more saturated so a monogram stays legible
 * against it at any avatar size.
 */
internal fun avatarTintFor(name: String): String =
    tintGradient(
        seed = name,
        angleDegrees = AVATAR_GRADIENT_ANGLE,
        firstSaturation = AVATAR_FIRST_SATURATION,
        firstLightness = AVATAR_FIRST_LIGHTNESS,
        secondSaturation = AVATAR_SECOND_SATURATION,
        secondLightness = AVATAR_SECOND_LIGHTNESS,
    )

/**
 * A user's own avatar image, authenticated by the cookie the browser already holds.
 *
 * Relative for the same reason [coverUrl] is: the server serves this bundle in the normal
 * deployment, and a cookie cannot cross origins — an absolute URL at another host would produce an
 * unauthenticated request rather than a working image.
 *
 * ⛔ **404 is the common case, not an error.** The endpoint serves bytes only for a user who has
 * uploaded a picture; everyone else has a colour and their initials, which is a complete avatar and
 * not a placeholder for a missing one. Any call site must therefore treat a failed load as
 * "monogram", never as "broken" — see [UserAvatar], which is the only sanctioned way to do it.
 */
internal fun avatarUrl(userId: String): String = "/api/v1/avatars/$userId"

private val AVATAR_WHITESPACE = Regex("\\s+")

private const val AVATAR_GRADIENT_ANGLE = 150

private const val AVATAR_FIRST_SATURATION = 42

private const val AVATAR_FIRST_LIGHTNESS = 20

private const val AVATAR_SECOND_SATURATION = 46

private const val AVATAR_SECOND_LIGHTNESS = 8
