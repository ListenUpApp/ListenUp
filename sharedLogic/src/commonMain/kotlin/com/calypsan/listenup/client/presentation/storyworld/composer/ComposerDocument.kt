package com.calypsan.listenup.client.presentation.storyworld.composer

import com.calypsan.listenup.api.core.MentionTokens

/**
 * One piece of a [ComposerDocument]'s content: either verbatim prose, or a resolved reference
 * to a Story World entity.
 *
 * A composer note is never stored as [Segment]s — it round-trips through [ComposerDocument.rawText]
 * as plain text decorated with [MentionTokens] tokens. Segments are this layer's in-memory
 * working representation, letting the UI render a mention as a styled chip while editing
 * continues to operate on the underlying wire text.
 */
internal sealed interface Segment {
    /** Verbatim prose. Never empty — [ComposerDocument] never carries an empty [Text] segment. */
    data class Text(
        val value: String,
    ) : Segment

    /**
     * A resolved mention of the entity identified by [entityId], displayed as [name].
     *
     * [name] is the *live* display name when known, falling back to the token's cached name
     * otherwise (see [MentionTokens]). It is what the UI renders inside the mention chip and
     * what [ComposerDocument.displayText] contributes for this segment.
     */
    data class Mention(
        val entityId: String,
        val name: String,
    ) : Segment
}

/** Which inline trigger is currently open at the cursor — see [ComposerDocument.activeTrigger]. */
internal enum class TriggerKind {
    /** An entity-mention trigger (`@` or `[`). */
    MENTION,

    /** A typed-verb trigger (`*`). */
    VERB,
}

/**
 * An open, in-progress trigger at the cursor: the user has typed a trigger character and is
 * now typing [query] to filter a suggestion list, but has not yet committed a selection.
 *
 * @property startOffset the display-text offset of the trigger character itself (not the query).
 */
internal data class Trigger(
    val kind: TriggerKind,
    val query: String,
    val startOffset: Int,
)

/**
 * The composer's editing model for a single Story World world-log note: a sequence of
 * [Segment]s (prose interleaved with resolved entity mentions) plus the caret position, both in
 * **display-text** coordinates (see [displayText]).
 *
 * This type is the pure engine behind the composer text field. It owns three responsibilities:
 * 1. Translating between the wire format ([rawText], `[[e:<id>|<cached name>]]` tokens — see
 *    [MentionTokens]) and the display format the user actually edits ([displayText], mention
 *    tokens collapsed to their name).
 * 2. Reconciling a raw text-field edit ([applyDisplayChange]) against the segment structure,
 *    including the rule that a partially-edited mention dissolves into plain text rather than
 *    silently truncating.
 * 3. Detecting an in-progress `@`/`[`/`*` trigger ([activeTrigger]) so the UI can show a
 *    suggestion list, and committing a selection from it ([insertMention]).
 *
 * Every mutating operation returns a new [ComposerDocument] — this type is immutable.
 */
internal data class ComposerDocument(
    val segments: List<Segment>,
    val cursor: Int,
) {
    /** Namespace for [fromRaw] and [empty] — see the extension functions on this companion below. */
    companion object

    /** Renders every segment as the user sees it: [Segment.Text] verbatim, [Segment.Mention] as its name. */
    fun displayText(): String =
        segments.joinToString(separator = "") { segment ->
            when (segment) {
                is Segment.Text -> segment.value
                is Segment.Mention -> segment.name
            }
        }

    /**
     * Renders every segment as the wire format: [Segment.Text] verbatim, [Segment.Mention] as a
     * [MentionTokens] token.
     */
    fun rawText(): String =
        segments.joinToString(separator = "") { segment ->
            when (segment) {
                is Segment.Text -> segment.value
                is Segment.Mention -> MentionTokens.token(segment.entityId, segment.name)
            }
        }

    /**
     * The display-text offset span of every [Segment.Mention], in document order — e.g. for the
     * UI to paint each mention's chip background over the right slice of the rendered text.
     */
    fun mentionSpans(): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var offset = 0
        for (segment in segments) {
            val length = segment.displayLength()
            if (segment is Segment.Mention) {
                spans += offset until offset + length
            }
            offset += length
        }
        return spans
    }

    /**
     * Commits a mention selection at the cursor.
     *
     * If [activeTrigger] reports an open trigger, the trigger character and its query are
     * replaced by the mention (the trigger's `@`/`[`/`*` is swallowed, not preserved). Otherwise
     * the mention is inserted at [cursor] with nothing removed. No space is appended after the
     * mention. The returned document's [cursor] lands immediately after the inserted mention.
     */
    fun insertMention(
        entityId: String,
        name: String,
    ): ComposerDocument {
        val trigger = activeTrigger()
        val start = trigger?.startOffset ?: cursor
        return spliceMention(start, cursor, entityId, name)
    }

    /**
     * Reconciles a raw text-field edit: the field now reads [newDisplay] with caret [newCursor],
     * replacing whatever [displayText] previously was.
     *
     * The edit is localized by diffing old vs. new display text via longest-common-prefix /
     * longest-common-suffix (the two never overlap). Any [Segment.Mention] whose span *strictly*
     * overlaps the resulting edit window dissolves entirely — it stops being a mention and its
     * surviving characters (from the new text) fold back in as plain [Segment.Text]. A mention
     * merely touched at one edge (typing immediately before or after it) is left intact.
     */
    fun applyDisplayChange(
        newDisplay: String,
        newCursor: Int,
    ): ComposerDocument {
        val oldDisplay = displayText()
        val oldLen = oldDisplay.length
        val newLen = newDisplay.length
        val sharedLen = minOf(oldLen, newLen)

        val prefixLen = commonPrefixLength(oldDisplay, newDisplay, sharedLen)
        val suffixLen = commonSuffixLength(oldDisplay, newDisplay, sharedLen - prefixLen)

        val (editStart, editEnd) = expandWindowForDissolvingMentions(segments, prefixLen, oldLen - suffixLen)
        val newEditEnd = newLen - (oldLen - editEnd)
        val middleText = newDisplay.substring(editStart, newEditEnd)

        val rebuilt = rebuildAroundEditWindow(segments, editStart, editEnd, middleText)
        return ComposerDocument(rebuilt, newCursor)
    }

    /**
     * Detects an in-progress `@`/`[`/`*` trigger ending at [cursor], scanning backward through
     * [displayText] for the nearest eligible trigger character.
     *
     * A trigger character is eligible only when all of the following hold:
     * - it sits inside a [Segment.Text] (never inside a [Segment.Mention]'s rendered name);
     * - it is at the very start of the text, or immediately preceded by whitespace;
     * - the query — the text from just after the trigger to [cursor] — contains no newline and
     *   no [Segment.Mention] span (a whole mention sitting between the trigger and the cursor
     *   means the trigger is stale, not in-progress).
     *
     * The query may otherwise contain spaces, so multi-word names ("King's Landing") and
     * multi-word verbs ("moves to") can be typed as a single in-progress query. The first trigger
     * character found scanning backward is decisive: if it fails eligibility, the result is `null`
     * — scanning does not continue past it to an earlier trigger.
     */
    fun activeTrigger(): Trigger? {
        val display = displayText()
        val spans = mentionSpans()
        for (index in cursor - 1 downTo 0) {
            if (spans.any { index in it }) continue
            val ch = display[index]
            if (ch == '\n') return null
            val kind = triggerKindOf(ch) ?: continue
            return resolveTrigger(display, spans, index, kind, cursor)
        }
        return null
    }

    /**
     * Splices a [Segment.Mention] into the display-text range `[start, end)`, dropping whatever
     * that range covered. Shared by [insertMention]; callers guarantee the range never partially
     * cuts a [Segment.Mention] (it lies within a single [Segment.Text], or is a zero-length point).
     */
    private fun spliceMention(
        start: Int,
        end: Int,
        entityId: String,
        name: String,
    ): ComposerDocument {
        val before = mutableListOf<Segment>()
        val after = mutableListOf<Segment>()
        var offset = 0
        for (segment in segments) {
            val length = segment.displayLength()
            val segStart = offset
            val segEnd = offset + length
            when {
                segEnd <= start -> before += segment
                segStart >= end -> after += segment
                segment is Segment.Text -> splitTextAroundWindow(segment, segStart, start, end, before, after)
                else -> Unit // A Mention fully inside [start, end) is dropped — callers never split one.
            }
            offset = segEnd
        }
        val merged = mergeAdjacentText(before + listOf(Segment.Mention(entityId, name)) + after)
        return ComposerDocument(merged, start + name.length)
    }
}

/** Display-text length contributed by this segment: [Segment.Text.value]'s length, or [Segment.Mention.name]'s. */
private fun Segment.displayLength(): Int =
    when (this) {
        is Segment.Text -> value.length
        is Segment.Mention -> name.length
    }

/**
 * Mirrors [MentionTokens]'s wire grammar for a *positional* parse — id, cached name, and match
 * range — that [MentionTokens.extractMentionIds] / [MentionTokens.render] don't expose (they
 * only report the mentioned ids and a fully-rendered string, never *where* each token sat).
 * Kept in exact lockstep with the private regex in that class: same delimiter shape, same
 * lazy-name-stops-at-first-`]]` semantics. Do not diverge from it without updating both.
 */
private val RAW_MENTION_TOKEN_REGEX = Regex("""\[\[e:([^|\[\]]+)\|([\s\S]*?)\]\]""")

/**
 * Parses [raw] wire text into a [ComposerDocument]: each well-formed [MentionTokens] token
 * becomes a [Segment.Mention] (preferring [nameFor]'s live name, falling back to the token's
 * cached name), and any malformed token-like sequence is left as literal [Segment.Text] — the
 * same well-formedness rule [MentionTokens] itself applies. The cursor starts at the end of the
 * resulting [ComposerDocument.displayText].
 */
internal fun ComposerDocument.Companion.fromRaw(
    raw: String,
    nameFor: (String) -> String?,
): ComposerDocument {
    val segments = mutableListOf<Segment>()
    var lastEnd = 0
    for (match in RAW_MENTION_TOKEN_REGEX.findAll(raw)) {
        val range = match.range
        if (range.first > lastEnd) {
            segments += Segment.Text(raw.substring(lastEnd, range.first))
        }
        val entityId = match.groupValues[1]
        val cachedName = match.groupValues[2]
        segments += Segment.Mention(entityId, nameFor(entityId) ?: cachedName)
        lastEnd = range.last + 1
    }
    if (lastEnd < raw.length) {
        segments += Segment.Text(raw.substring(lastEnd))
    }
    val merged = mergeAdjacentText(segments)
    return ComposerDocument(merged, merged.sumOf { it.displayLength() })
}

/** The empty document: no segments, cursor at the start. */
internal fun ComposerDocument.Companion.empty(): ComposerDocument = ComposerDocument(segments = emptyList(), cursor = 0)

private fun commonPrefixLength(
    a: String,
    b: String,
    limit: Int,
): Int {
    var length = 0
    while (length < limit && a[length] == b[length]) length++
    return length
}

private fun commonSuffixLength(
    a: String,
    b: String,
    limit: Int,
): Int {
    var length = 0
    while (length < limit && a[a.length - 1 - length] == b[b.length - 1 - length]) length++
    return length
}

private fun rangesStrictlyOverlap(
    aStart: Int,
    aEnd: Int,
    bStart: Int,
    bEnd: Int,
): Boolean = aStart < bEnd && bStart < aEnd

/**
 * Grows `[initialStart, initialEnd)` until it fully contains every [Segment.Mention] it
 * overlaps — a mention is atomic, so one that only partially overlaps the raw diff window must
 * still dissolve as a whole, which means the window has to widen to swallow it completely. The
 * expansion can cascade (widening to include one mention might newly overlap a neighbor), so
 * this runs to a fixed point.
 */
private fun expandWindowForDissolvingMentions(
    segments: List<Segment>,
    initialStart: Int,
    initialEnd: Int,
): Pair<Int, Int> {
    var start = initialStart
    var end = initialEnd
    var changed = true
    while (changed) {
        changed = false
        var offset = 0
        for (segment in segments) {
            val length = segment.displayLength()
            val segStart = offset
            val segEnd = offset + length
            if (segment is Segment.Mention && rangesStrictlyOverlap(segStart, segEnd, start, end)) {
                if (segStart < start) {
                    start = segStart
                    changed = true
                }
                if (segEnd > end) {
                    end = segEnd
                    changed = true
                }
            }
            offset = segEnd
        }
    }
    return start to end
}

/**
 * Rebuilds the segment list around the edit window `[editStart, editEnd)` (old-display
 * coordinates): segments entirely before or after the window are kept as-is, a [Segment.Text]
 * segment straddling the window is split at its boundaries, and any [Segment.Mention] the window
 * touches (which — thanks to [expandWindowForDissolvingMentions] — is always fully inside it)
 * dissolves. [middleText] becomes the new [Segment.Text] in its place.
 */
private fun rebuildAroundEditWindow(
    segments: List<Segment>,
    editStart: Int,
    editEnd: Int,
    middleText: String,
): List<Segment> {
    val before = mutableListOf<Segment>()
    val after = mutableListOf<Segment>()
    var offset = 0
    for (segment in segments) {
        val length = segment.displayLength()
        val segStart = offset
        val segEnd = offset + length
        when {
            segEnd <= editStart -> before += segment
            segStart >= editEnd -> after += segment
            segment is Segment.Text -> splitTextAroundWindow(segment, segStart, editStart, editEnd, before, after)
            else -> Unit // A Mention overlapping the window dissolves entirely — its text is not preserved.
        }
        offset = segEnd
    }
    val middle = if (middleText.isEmpty()) emptyList() else listOf(Segment.Text(middleText))
    return mergeAdjacentText(before + middle + after)
}

/**
 * Splits a [Segment.Text] starting at [segStart] around the window `[windowStart, windowEnd)`,
 * appending whatever survives before the window to [before] and whatever survives after it to
 * [after]. Shared by [rebuildAroundEditWindow] and [ComposerDocument.spliceMention].
 */
private fun splitTextAroundWindow(
    segment: Segment.Text,
    segStart: Int,
    windowStart: Int,
    windowEnd: Int,
    before: MutableList<Segment>,
    after: MutableList<Segment>,
) {
    val length = segment.value.length
    val keepBeforeLength = (windowStart - segStart).coerceIn(0, length)
    val keepAfterStart = (windowEnd - segStart).coerceIn(0, length)
    val beforePart = segment.value.substring(0, keepBeforeLength)
    val afterPart = segment.value.substring(keepAfterStart)
    if (beforePart.isNotEmpty()) before += Segment.Text(beforePart)
    if (afterPart.isNotEmpty()) after += Segment.Text(afterPart)
}

/** Merges consecutive [Segment.Text] entries into one and drops any empty [Segment.Text]. */
private fun mergeAdjacentText(segments: List<Segment>): List<Segment> {
    val merged = mutableListOf<Segment>()
    for (segment in segments) {
        if (segment is Segment.Text && segment.value.isEmpty()) continue
        val previous = merged.lastOrNull()
        if (segment is Segment.Text && previous is Segment.Text) {
            merged[merged.lastIndex] = Segment.Text(previous.value + segment.value)
        } else {
            merged += segment
        }
    }
    return merged
}

private fun triggerKindOf(ch: Char): TriggerKind? =
    when (ch) {
        '@', '[' -> TriggerKind.MENTION
        '*' -> TriggerKind.VERB
        else -> null
    }

/**
 * Applies the eligibility rules documented on [ComposerDocument.activeTrigger] to the trigger
 * character found at [triggerIndex], returning the resulting [Trigger] or `null`.
 */
private fun resolveTrigger(
    display: String,
    spans: List<IntRange>,
    triggerIndex: Int,
    kind: TriggerKind,
    cursor: Int,
): Trigger? {
    val precededByBoundary = triggerIndex == 0 || display[triggerIndex - 1].isWhitespace()
    if (!precededByBoundary) return null
    val query = display.substring(triggerIndex + 1, cursor)
    if (query.contains('\n')) return null
    val queryCrossesMention = spans.any { rangesStrictlyOverlap(it.first, it.last + 1, triggerIndex, cursor) }
    if (queryCrossesMention) return null
    return Trigger(kind, query, triggerIndex)
}
