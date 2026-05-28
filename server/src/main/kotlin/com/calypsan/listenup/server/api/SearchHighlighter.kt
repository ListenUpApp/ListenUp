package com.calypsan.listenup.server.api

/** STX/ETX sentinels wrapping a matched span. The client splits on these to bold matches. */
const val HL_START = ''
const val HL_END = ''

/**
 * Returns [field] with each whole-word token from [query] (case-insensitive, the same
 * `[A-Za-z0-9]` tokenization the FTS sanitizer uses) wrapped in [HL_START]/[HL_END];
 * `null` when [field] is null/blank or no token matched. Whole-word matching mirrors FTS5
 * tokenization — `kings` does not highlight inside `Kingsman`.
 */
fun highlightMatches(
    field: String?,
    query: String,
): String? {
    if (field.isNullOrBlank()) return null
    val tokens =
        query
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
            .toSet()
    if (tokens.isEmpty()) return null
    var matched = false
    val sb = StringBuilder()
    var last = 0
    for (m in Regex("[A-Za-z0-9]+").findAll(field)) {
        sb.append(field, last, m.range.first)
        val word = m.value
        if (word.lowercase() in tokens) {
            sb.append(HL_START).append(word).append(HL_END)
            matched = true
        } else {
            sb.append(word)
        }
        last = m.range.last + 1
    }
    sb.append(field, last, field.length)
    return if (matched) sb.toString() else null
}
