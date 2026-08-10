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
     * Checks [candidate] and burns the token on success. Returns false for disarmed, expired,
     * already-consumed, and simply-wrong — the caller must map all four to one error so the
     * outside world cannot tell them apart.
     */
    fun consume(
        candidate: String,
        now: Instant,
    ): Boolean {
        val at = armedAt ?: return false
        if (now > at.plus(WINDOW)) return false
        if (candidate != token) return false
        return consumed.compareAndSet(expect = false, update = true)
    }

    companion object {
        /** How long after startup the hatch stays open. */
        val WINDOW = 15.minutes

        /** Not armed. Every [consume] fails. */
        fun disarmed(): RootResetToken = RootResetToken(token = "", armedAt = null)

        /** Armed at [clock]'s current instant with a freshly minted token. */
        fun armed(clock: Clock): RootResetToken =
            RootResetToken(
                token = InviteCodeGenerator().generate(),
                armedAt = clock.now(),
            )
    }
}
