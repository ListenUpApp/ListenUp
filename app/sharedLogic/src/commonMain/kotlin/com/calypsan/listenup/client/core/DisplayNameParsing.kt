package com.calypsan.listenup.client.core

/**
 * Splits a combined display name into `(firstName, lastName)`. The first whitespace-delimited token
 * is the first name; the remainder (joined) is the last name. Blank input yields `("", "")`.
 *
 * Heuristic: registration stores only a joined `displayName`, so this reconstructs editable
 * first/last fields. Names with more than two words put the remainder in the last name.
 */
fun splitDisplayName(displayName: String): Pair<String, String> {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return "" to ""
    val first = trimmed.substringBefore(' ')
    val last = trimmed.substringAfter(' ', missingDelimiterValue = "").trim()
    return first to last
}

/**
 * Resolves the first/last names to seed an edit form: prefers stored non-blank [firstName]/
 * [lastName], otherwise derives them from [displayName] via [splitDisplayName].
 */
fun resolveNameFields(
    displayName: String,
    firstName: String?,
    lastName: String?,
): Pair<String, String> {
    val storedFirst = firstName?.trim().orEmpty()
    val storedLast = lastName?.trim().orEmpty()
    return if (storedFirst.isNotEmpty() || storedLast.isNotEmpty()) {
        storedFirst to storedLast
    } else {
        splitDisplayName(displayName)
    }
}
