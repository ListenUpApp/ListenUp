package com.calypsan.listenup.api.core

/**
 * Parses and renders `@entity` mention tokens embedded in free text — the encoding shared by
 * every free-text field in the Story World unified log (event descriptions, notes, and any
 * future free-text surface that lets a caller reference a Story World entity inline).
 *
 * ## Wire format
 *
 * A mention token is written as:
 *
 * ```
 * [[e:<entityId>|<cached display name>]]
 * ```
 *
 * - `[[e:` is a literal prefix identifying an *entity* mention (the `e:` tag leaves room for
 *   future mention kinds under a different letter without touching this grammar).
 * - `<entityId>` is the mentioned entity's id — a UUID string in practice, though this parser
 *   does not itself validate UUID shape. It is any non-empty run of characters up to the next
 *   `|`, `[`, or `]`.
 * - `<cached display name>` is the entity's display name **as it was at write time**. It is a
 *   fallback only: [render] always prefers a live name lookup and falls back to this cached name
 *   when the entity has since been renamed, deleted, or is otherwise unknown to the caller.
 *   Without this cache, a deleted entity would render as nothing at all.
 * - The token closes at the **first** `]]` found after the `|` — name matching is lazy, not
 *   greedy. This is exactly why [token] sanitizes `]]` out of the cached name at write time (see
 *   Escaping below): an un-escaped `]]` inside the name would truncate the name early and leave
 *   the true closing `]]` as trailing literal text.
 *
 * This grammar is **wire-stable**: it is stored as plain text in the free-text fields it
 * decorates, round-trips through the world-event log like any other field content, and is read
 * by third-party integrations consuming those same fields over REST. Do not change the token
 * shape without a migration plan — every previously-written token must keep parsing.
 *
 * ## Escaping
 *
 * [token] is the only writer, and it sanitizes the cached display name so the produced token
 * always parses back to the same id and a legible fallback name:
 *
 * - `|` (the field separator) becomes `¦` (U+00A6 BROKEN BAR) — visually near-identical, and
 *   never collides with the grammar.
 * - `]]` (the close-delimiter) becomes `] ]` (a space inserted between the two brackets). A lone
 *   `]` is left untouched — it never terminates a token on its own.
 *
 * Entity ids are UUIDs and never contain `|`, `[`, or `]`, so they are never escaped.
 *
 * ## Malformed input
 *
 * Free text is user-authored (and user-edited after the fact) and may contain a `[[e:` sequence
 * that never resolves to a well-formed token — an unterminated token with no closing `]]`, an
 * empty id (`[[e:|name]]`), or a `[[e:` with no `|` at all. [extractMentionIds] and [render]
 * agree on exactly the same well-formedness rule and both treat a malformed sequence as
 * **ordinary literal text** — it is never dropped, and neither function ever throws on it.
 */
object MentionTokens {
    /**
     * Matches a single well-formed mention token, capturing `(entityId, cachedDisplayName)` in
     * groups 1 and 2.
     *
     * The id group excludes `|`, `[`, `]` so it can never itself swallow the delimiter or a
     * stray bracket — this is what makes an unpiped sequence like `[[e:abc]]` fail to match
     * (correctly malformed) rather than mis-parse. The name group is lazy (`.*?`) so it stops at
     * the first `]]` it finds, matching the class KDoc's "greedy to the first `]]` after the
     * `|`" rule; [RegexOption.DOT_MATCHES_ALL] lets a cached name span an embedded newline
     * without changing that behavior.
     */
    private val MENTION_TOKEN_REGEX =
        Regex("""\[\[e:([^|\[\]]+)\|(.*?)\]\]""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Returns the entity ids of every well-formed mention token in [text], deduplicated.
     *
     * The result is a [Set]: order is not preserved and repeated mentions of the same entity
     * collapse to one id. Malformed token-like sequences (see the class KDoc's Malformed input
     * section) are silently ignored — they are ordinary text, not entity ids.
     */
    fun extractMentionIds(text: String): Set<String> =
        MENTION_TOKEN_REGEX.findAll(text).map { it.groupValues[1] }.toSet()

    /**
     * Replaces every well-formed mention token in [text] with a display name, for read surfaces
     * that render free text to a human.
     *
     * For each token, [nameFor] is called with the mentioned entity's id. A non-null result is
     * used verbatim (the live name). A null result — the entity was deleted, or is otherwise
     * unknown to this caller — falls back to the cached display name embedded in the token
     * itself at write time. Text outside tokens, and any malformed token-like sequence (see the
     * class KDoc's Malformed input section), passes through unchanged.
     */
    fun render(
        text: String,
        nameFor: (String) -> String?,
    ): String =
        MENTION_TOKEN_REGEX.replace(text) { match ->
            val entityId = match.groupValues[1]
            val cachedDisplayName = match.groupValues[2]
            nameFor(entityId) ?: cachedDisplayName
        }

    /**
     * Builds a well-formed mention token for [entityId], caching [displayName] as the
     * render-time fallback.
     *
     * Sanitizes [displayName] so the produced token always parses back to exactly [entityId] and
     * a legible (if slightly altered) fallback name — see the class KDoc's Escaping section for
     * the two substitutions applied. Callers write this token into a free-text field in place of
     * a raw `@name` reference; they never hand-construct the token shape themselves.
     */
    fun token(
        entityId: String,
        displayName: String,
    ): String {
        val sanitizedDisplayName = displayName.replace("|", "¦").replace("]]", "] ]")
        return "[[e:$entityId|$sanitizedDisplayName]]"
    }
}
