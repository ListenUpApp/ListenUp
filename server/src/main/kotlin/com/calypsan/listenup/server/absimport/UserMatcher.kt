package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.MatchTier
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
 * `username == displayName`. A tier matching **exactly one** ListenUp user yields a
 * [MatchTier.STRONG] suggestion. A tier matching **more than one** stops the search and yields
 * [MatchTier.AMBIGUOUS] with no suggestion — picking one arbitrarily could import one user's progress
 * onto another's library, so a collision is surfaced for the admin rather than guessed. No match in
 * either tier leaves [AbsUserMatch.suggestedUserId] null with [MatchTier.UNMATCHED] — the admin then
 * maps it by hand. There is no fuzzy matching: an unsure match is no match.
 */
internal class UserMatcher {
    /** The outcome of one tier: the resolved user (one candidate) or AMBIGUOUS (many candidates). */
    private sealed interface TierResult {
        data class Resolved(
            val userId: UserId,
        ) : TierResult

        data object Ambiguous : TierResult
    }

    /** Builds the suggested mapping for [absUser] against the live ListenUp user list. */
    fun match(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): AbsUserMatch {
        val result = matchByEmail(absUser, listenupUsers) ?: matchByName(absUser, listenupUsers)
        val suggestion = (result as? TierResult.Resolved)?.userId
        val confidence =
            when (result) {
                is TierResult.Resolved -> MatchTier.STRONG
                TierResult.Ambiguous -> MatchTier.AMBIGUOUS
                null -> MatchTier.UNMATCHED
            }
        return AbsUserMatch(
            absUserId = AbsUserId(absUser.id),
            absUsername = absUser.username,
            absEmail = absUser.email,
            suggestedUserId = suggestion,
            confidence = confidence,
        )
    }

    private fun matchByEmail(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): TierResult? {
        val email = absUser.email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return resolve(listenupUsers.filter { it.email.equals(email, ignoreCase = true) })
    }

    private fun matchByName(
        absUser: AbsUser,
        listenupUsers: List<MatchableUser>,
    ): TierResult? {
        val username = normalizeText(absUser.username)
        if (username.isEmpty()) return null
        return resolve(listenupUsers.filter { normalizeText(it.displayName) == username })
    }

    /** Exactly one → resolved; more than one → ambiguous; none → null (try the next tier). */
    private fun resolve(candidates: List<MatchableUser>): TierResult? =
        when (candidates.size) {
            0 -> null
            1 -> TierResult.Resolved(candidates.first().id)
            else -> TierResult.Ambiguous
        }
}
