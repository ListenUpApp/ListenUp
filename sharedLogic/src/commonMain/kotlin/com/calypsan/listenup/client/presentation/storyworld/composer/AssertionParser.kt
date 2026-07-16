package com.calypsan.listenup.client.presentation.storyworld.composer

import com.calypsan.listenup.api.sync.WorldEventType

/**
 * A typed world-event assertion derived from a composer note's prose — see [AssertionParser].
 *
 * Stored only in [WorldEventType]-typed columns; the note's text itself is left untouched (plain
 * prose plus mention tokens). The chip the composer shows for a detected assertion is rendered
 * from this value, never re-derived from the raw text a second time.
 */
internal data class Assertion(
    val type: WorldEventType,
    val subjectEntityId: String,
    val objectEntityId: String?,
)

/**
 * Derives at most one [Assertion] from a composer note's [Segment]s, recognizing a closed,
 * case-insensitive verb vocabulary written as plain prose between two mentions —
 * `[Eddard] leaves [Winterfell]` — or, for verbs that don't require a destination, between a
 * mention and the end of the sentence — `[Eddard] leaves`.
 *
 * ## Vocabulary
 *
 * | Verb(s) | Two-mention form (`subject verb object`) | Subject-only form (`subject verb …`) |
 * |---|---|---|
 * | `enters` | [WorldEventType.ENTERS_SCENE] | [WorldEventType.ENTERS_SCENE] |
 * | `moves to`, `arrives at`, `travels to` | [WorldEventType.MOVES_TO] (object required) | does not parse |
 * | `departs` | [WorldEventType.DEPARTS] | [WorldEventType.DEPARTS] |
 * | `leaves` | [WorldEventType.DEPARTS] | [WorldEventType.EXITS_SCENE] |
 *
 * The two-mention form only binds an object when the text *between* the two mentions is,
 * trimmed, *exactly* one of the two-mention verbs above (case-insensitive) — "leaves for" is not
 * "leaves", so it never binds an object even though a mention follows it. The subject-only form
 * matches when the text, left-trimmed, *starts with* one of its verbs followed by either the end
 * of the segment or whitespace (so trailing prose like "departs for the south" still resolves to
 * a plain [WorldEventType.DEPARTS] with no object).
 *
 * Segments are scanned left to right; the first position that yields any assertion — bound or
 * subject-only — wins. A note that matches no pattern anywhere yields `null`.
 */
internal object AssertionParser {
    private val TWO_MENTION_VERBS: Map<String, WorldEventType> =
        mapOf(
            "enters" to WorldEventType.ENTERS_SCENE,
            "moves to" to WorldEventType.MOVES_TO,
            "arrives at" to WorldEventType.MOVES_TO,
            "travels to" to WorldEventType.MOVES_TO,
            "departs" to WorldEventType.DEPARTS,
            "leaves" to WorldEventType.DEPARTS,
        )

    private val SUBJECT_ONLY_VERBS: Map<String, WorldEventType> =
        mapOf(
            "enters" to WorldEventType.ENTERS_SCENE,
            "departs" to WorldEventType.DEPARTS,
            "leaves" to WorldEventType.EXITS_SCENE,
        )

    /** See the class KDoc for the full vocabulary and matching rules. */
    fun parse(segments: List<Segment>): Assertion? {
        for (index in segments.indices) {
            val subject = segments[index] as? Segment.Mention ?: continue
            val verbText = segments.getOrNull(index + 1) as? Segment.Text ?: continue
            val objectMention = segments.getOrNull(index + 2) as? Segment.Mention

            val bound = objectMention?.let { parseTwoMentionVerb(verbText.value, subject.entityId, it.entityId) }
            if (bound != null) return bound

            val subjectOnly = parseSubjectOnlyVerb(verbText.value, subject.entityId)
            if (subjectOnly != null) return subjectOnly
        }
        return null
    }

    private fun parseTwoMentionVerb(
        text: String,
        subjectEntityId: String,
        objectEntityId: String,
    ): Assertion? {
        val type = TWO_MENTION_VERBS[text.trim().lowercase()] ?: return null
        return Assertion(type, subjectEntityId, objectEntityId)
    }

    private fun parseSubjectOnlyVerb(
        text: String,
        subjectEntityId: String,
    ): Assertion? {
        val trimmed = text.trimStart()
        val type = SUBJECT_ONLY_VERBS.entries.firstOrNull { (verb, _) -> matchesSubjectOnlyVerb(trimmed, verb) }?.value
        return type?.let { Assertion(it, subjectEntityId, objectEntityId = null) }
    }

    private fun matchesSubjectOnlyVerb(
        trimmed: String,
        verb: String,
    ): Boolean {
        if (!trimmed.startsWith(verb, ignoreCase = true)) return false
        return trimmed.length == verb.length || trimmed[verb.length].isWhitespace()
    }
}
