package com.calypsan.listenup.client.presentation.contributoredit

/**
 * Normalizes a contributor name for punctuation/spacing-insensitive comparison.
 *
 * Users end up with duplicate contributors from punctuation variants of the same name —
 * "George R.R. Martin" vs "George R. R. Martin", "James S.A. Corey" vs "James S. A. Corey".
 * This normalization treats those as equal: lowercase, then periods become word boundaries
 * (replaced with a space), then whitespace runs collapse to a single space, then the result
 * is trimmed. It intentionally does NOT treat differently-spelled names as equal — "George
 * Martin" and "George R.R. Martin" normalize to different strings.
 *
 * Used by [ContributorEditViewModel] to detect a rename collision. The server has its own
 * copy of this exact logic (`normalizeContributorName` in `ContributorServiceImpl.kt`,
 * server-side) — the two must be kept in lockstep by hand, since sharing a single function
 * across the client/server boundary would require a new contract/commonMain public type for
 * four lines of logic.
 */
internal fun normalizeContributorName(name: String): String =
    name
        .lowercase()
        .replace('.', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
