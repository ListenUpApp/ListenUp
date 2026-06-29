package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Seeds the demo profile's invite surface — one unclaimed, pending invite so a
 * developer running the demo server can immediately exercise the admin invite
 * list and the public claim flow without first minting an invite by hand.
 *
 * Runs after [UserDomainSeeder] (order 0): minting an invite is admin-gated, so
 * the seeder resolves the demo ROOT that user-seeding created and acts as that
 * principal — mirroring `Application.SYSTEM_BOOTSTRAP_PRINCIPAL`. It writes through
 * [InviteServiceImpl.createInvite] (never raw SQL), so the seeded row is
 * indistinguishable from an admin-minted one — `created_by` is the real root.
 *
 * The seeded email is intentionally well-known and documented; the demo profile is
 * never for production use. Expiry uses the service default so the demo invite stays
 * claimable across a normal demo session.
 */
class InviteDomainSeeder(
    private val db: ListenUpDatabase,
    private val inviteService: InviteServiceImpl,
) : DomainSeeder {
    override val domainName: String = "invite"

    // After UserDomainSeeder (0) — createInvite needs the demo root to exist as created_by.
    override val order: Int = 1

    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(db) {
            db.invitesQueries.hasAnyInvite().executeAsOne()
        }

    override suspend fun seed() {
        val rootPrincipal = resolveRootPrincipal()
        if (rootPrincipal == null) {
            logger.warn { "seed: no ROOT user found — invite not created (UserDomainSeeder should run first)" }
            return
        }
        val scoped = inviteService.copyWith(PrincipalProvider { rootPrincipal })
        when (
            val result =
                scoped.createInvite(
                    email = INVITE_EMAIL,
                    displayName = INVITE_DISPLAY_NAME,
                    role = UserRole.MEMBER,
                    expiresInDays = null,
                )
        ) {
            is AppResult.Success -> logger.info { "seed: demo invite for '$INVITE_EMAIL' created" }
            is AppResult.Failure -> logger.warn { "seed: demo invite not created — ${result.error.code}" }
        }
    }

    /**
     * The demo ROOT, as a [UserPrincipal] suitable for the admin gate. Built with a
     * synthetic [SessionId] — the seeder never reads it; `createInvite` only consults
     * `userId` and `role`. Returns null if no ROOT exists.
     */
    private suspend fun resolveRootPrincipal(): UserPrincipal? =
        suspendTransaction(db) {
            db.usersQueries
                .selectFirstByRole(role = UserRoleColumn.ROOT.name)
                .executeAsOneOrNull()
                ?.let { rootId ->
                    UserPrincipal(UserId(rootId), SessionId("seed-invite-$rootId"), UserRole.ROOT)
                }
        }

    companion object {
        const val INVITE_EMAIL = "invitee@listenup.app"
        const val INVITE_DISPLAY_NAME = "Invited Friend"
    }
}
