package com.calypsan.listenup.domain.storyworld

/**
 * Parses and renders `@entity` mentions embedded in Story World free text — the encoding shared by every
 * free-text field that can name an entity inline (a world event's text today). Client and server share it,
 * so the server derives exactly the mentions a client displays.
 *
 * ## Wire format
 *
 * ```
 * [[e:<entityId>|<cached display name>]]
 * ```
 *
 * - `[[e:` marks an *entity* mention; the `e:` tag leaves room for other mention kinds under another letter.
 * - `<entityId>` is any non-empty run of characters up to the next `|`, `[` or `]` — a UUID in practice.
 * - `<cached display name>` is the entity's name **at write time**, a fallback only: [render] prefers a live
 *   name and falls back to this one when the entity was renamed, deleted or is unknown to the caller.
 * - The token closes at the **first** `]]` after the `|` (lazy), which is why [token] escapes `]]` in names.
 *
 * The grammar is **wire-stable**: tokens are stored as plain text and round-trip through sync. Do not change
 * the shape without a migration plan — every token already written must keep parsing.
 *
 * ## Escaping
 *
 * [token] is the only writer. It replaces `|` with `¦` (U+00A6) and `]]` with `] ]`, so a written token always
 * parses back to its id and a legible name. A lone `]` is left as it is.
 *
 * ## Malformed input
 *
 * An unterminated token, an empty id (`[[e:|name]]`) or a `[[e:` with no `|` is ordinary literal text:
 * [extractMentionIds] ignores it and [render] passes it through. Neither ever throws.
 */
object MentionTokens {
    /**
     * One well-formed token, capturing `(entityId, cachedDisplayName)`. The id group excludes `|`, `[`, `]`;
     * the name group is lazy and uses `[\s\S]` (not `.` + `DOT_MATCHES_ALL`, which is JVM-only) so a cached
     * name may span a newline on every Kotlin target.
     */
    private val MENTION_TOKEN_REGEX = Regex("""\[\[e:([^|\[\]]+)\|([\s\S]*?)\]\]""")

    /** The entity ids of every well-formed token in [text], deduplicated; malformed sequences are ignored. */
    fun extractMentionIds(text: String): Set<String> = MENTION_TOKEN_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    /**
     * [text] with each well-formed token replaced by [nameFor] of its id, or by the token's cached name when
     * [nameFor] returns null. Text outside tokens, and malformed sequences, pass through unchanged.
     */
    fun render(
        text: String,
        nameFor: (String) -> String?,
    ): String =
        MENTION_TOKEN_REGEX.replace(text) { match ->
            nameFor(match.groupValues[1]) ?: match.groupValues[2]
        }

    /** A well-formed token for [entityId], caching a sanitised [displayName] (see Escaping). */
    fun token(
        entityId: String,
        displayName: String,
    ): String = "[[e:$entityId|${displayName.replace("|", "¦").replace("]]", "] ]")}]]"
}
