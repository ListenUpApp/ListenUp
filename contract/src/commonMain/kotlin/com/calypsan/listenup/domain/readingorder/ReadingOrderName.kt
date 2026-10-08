package com.calypsan.listenup.domain.readingorder

/**
 * The one rule for reading-order names (#962), shared by client and server so "already exists" means the
 * same on both sides: trimmed, 1..[MAX_LENGTH] characters, unique per series by [normalize] (case and runs
 * of whitespace ignored — the same rule series names use).
 */
object ReadingOrderName {
    /** Longest name a reading order may have, in UTF-16 chars, after trimming. */
    const val MAX_LENGTH: Int = 80

    private val WHITESPACE = Regex("\\s+")

    /** The trimmed name, or null when it is blank or longer than [MAX_LENGTH]. */
    fun validate(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /** The uniqueness key: lowercase, trimmed, whitespace runs collapsed. */
    fun normalize(name: String): String = name.lowercase().trim().replace(WHITESPACE, " ")
}
