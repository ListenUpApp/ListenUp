@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.atomicfu.atomic

/**
 * The root-password escape hatch's armed state.
 *
 * Root has no admin above them, so the ordinary approval flow cannot help. The authorisation
 * here is **host access**: anyone who can set `LISTENUP_ROOT_RESET` on the server can already
 * read the database and dump the password hashes, so requiring that is no weaker than what an
 * attacker would otherwise need.
 *
 * The flag alone is deliberately *not* sufficient. The likely failure is an operator who sets
 * the variable in a compose file, resets, and forgets to remove it — every subsequent restart
 * would silently re-arm the instance. So arming also prints a one-time token to the log, and
 * that token is required. A stale flag on its own grants nothing.
 */
class RootResetToken private constructor(
    val token: String,
    private val armedAt: Instant?,
) {
    private val consumed = atomic(false)

    /**
     * Checks [candidate] and burns the token on success, returning exactly why not otherwise.
     * The four rejection reasons ([ConsumeOutcome.Rejected]) exist for **server-side logging
     * only** — see that type's KDoc. Every caller that surfaces a result to an unauthenticated
     * caller must collapse [ConsumeOutcome.Rejected] to one client-visible shape regardless of
     * its [ConsumeOutcome.Reason], so the outside world cannot tell them apart.
     */
    fun consume(
        candidate: String,
        now: Instant,
    ): ConsumeOutcome {
        val at = armedAt ?: return ConsumeOutcome.Rejected(ConsumeOutcome.Reason.UNARMED)
        if (now > at.plus(WINDOW)) return ConsumeOutcome.Rejected(ConsumeOutcome.Reason.EXPIRED)
        if (candidate != token) return ConsumeOutcome.Rejected(ConsumeOutcome.Reason.WRONG_TOKEN)
        return if (consumed.compareAndSet(expect = false, update = true)) {
            ConsumeOutcome.Consumed
        } else {
            ConsumeOutcome.Rejected(ConsumeOutcome.Reason.ALREADY_CONSUMED)
        }
    }

    /**
     * True while the hatch can still be opened: armed, inside [WINDOW], token not yet burned.
     * Read-only — never mutates state, so surfaces like `ServerInfo.rootResetArmed` can poll it
     * freely without spending anything.
     */
    fun isLive(now: Instant): Boolean {
        val at = armedAt ?: return false
        if (consumed.value) return false
        return now <= at.plus(WINDOW)
    }

    companion object {
        /** How long after startup the hatch stays open. */
        val WINDOW = 15.minutes

        /** Not armed. Every [consume] is [ConsumeOutcome.Rejected]. */
        fun disarmed(): RootResetToken = RootResetToken(token = "", armedAt = null)

        /** Armed at [clock]'s current instant with a freshly minted token. */
        fun armed(clock: Clock): RootResetToken =
            RootResetToken(
                token = InviteCodeGenerator().generate(),
                armedAt = clock.now(),
            )
    }
}

/**
 * The outcome of a [RootResetToken.consume] attempt.
 *
 * [Rejected.reason] exists purely as a server-side diagnostic — an operator locked out of root
 * mid-incident with no log line at all has no way to tell "you set the wrong env var" from "you
 * mistyped the token" from "that token already expired". It must **never** reach a client:
 * collapsing unarmed / expired / wrong-token / already-consumed into one indistinguishable
 * [com.calypsan.listenup.api.error.AuthError.RootResetUnavailable] is the entire point of this
 * design (see [RootResetToken]'s KDoc) — a caller who could read [reason] back could use it to
 * probe which branch they hit.
 */
sealed interface ConsumeOutcome {
    /** The token matched, was unexpired, and had not yet been used — it is now burned. */
    data object Consumed : ConsumeOutcome

    /** The attempt was rejected. [reason] is for logs only — never the client response. */
    data class Rejected(
        val reason: Reason,
    ) : ConsumeOutcome

    /** Why a [Rejected] attempt failed. Log-only — see [Rejected]'s KDoc. */
    enum class Reason {
        /** `LISTENUP_ROOT_RESET` was not set at boot — the hatch was never armed. */
        UNARMED,

        /** The token was armed but its [RootResetToken.WINDOW] has closed. */
        EXPIRED,

        /** A token was already consumed — this is a replay. */
        ALREADY_CONSUMED,

        /** The candidate did not match the armed token. */
        WRONG_TOKEN,
    }
}
