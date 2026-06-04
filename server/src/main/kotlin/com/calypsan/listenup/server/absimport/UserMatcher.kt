package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.import.AbsUserMatch
import com.calypsan.listenup.api.dto.import.MatchTier
import com.calypsan.listenup.core.AbsUserId

/** A ListenUp user reduced to the fields the [UserMatcher] needs. */
internal data class MatchableUser(
    val id: UserId,
    val email: String,
    val displayName: String,
)

/**
 * Suggests a ListenUp user for an Audiobookshelf user, conservatively.
 *
 * Two tiers, both requiring an exact match: case-insensitive email equality, then normalized
 * `username == displayName`. The first that hits yields a [MatchTier.STRONG] suggestion. No match in
 * either tier leaves [AbsUserMatch.suggestedUserId] null with [MatchTier.UNMATCHED] — the admin then
 * maps it by hand. There is no fuzzy matching: a wrong suggestion would import one user's progress
 * onto another's library, so an unsure match is no match.
 */
internal class UserMatcher {
    /** Builds the suggested mapping for [absUser] against the live ListenUp user list. */
    fun match(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): AbsUserMatch {
        val suggestion = matchByEmail(absUser, listenupUsers) ?: matchByName(absUser, listenupUsers)
        return AbsUserMatch(
            absUserId = AbsUserId(absUser.id),
            absUsername = absUser.username,
            absEmail = absUser.email,
            suggestedUserId = suggestion,
            confidence = if (suggestion != null) MatchTier.STRONG else MatchTier.UNMATCHED,
        )
    }

    private fun matchByEmail(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): UserId? {
        val email = absUser.email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return listenupUsers.firstOrNull { it.email.equals(email, ignoreCase = true) }?.id
    }

    private fun matchByName(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): UserId? {
        val username = normalizeText(absUser.username)
        if (username.isEmpty()) return null
        return listenupUsers.firstOrNull { normalizeText(it.displayName) == username }?.id
    }
}
