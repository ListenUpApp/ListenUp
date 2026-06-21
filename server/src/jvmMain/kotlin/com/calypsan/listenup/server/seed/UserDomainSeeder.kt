package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Seeds the demo profile's users — known accounts so a developer running the demo
 * server can log straight in and exercise the multi-user surface:
 *  - a ROOT user via [AuthServiceImpl.setupRoot] (the real auth write-path);
 *  - a normal MEMBER via [AuthServiceImpl.register] (full `canEdit`/`canShare`);
 *  - a permission-restricted MEMBER with `canEdit`/`canShare` revoked, so the gated
 *    surfaces (book edits, collection sharing) have an obvious deny case to demo.
 *
 * Members register under the OPEN policy the demo profile runs with, so they land
 * ACTIVE. The restricted member's flags are revoked with a direct [updatePermissions]
 * query after registration since [RegisterRequest] carries no permission fields.
 *
 * Demo credentials are intentionally well-known and documented; the demo profile is
 * never for production use.
 */
class UserDomainSeeder(
    private val sql: ListenUpDatabase,
    private val authService: AuthServiceImpl,
) : DomainSeeder {
    override val domainName: String = "user"
    override val order: Int = 0

    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(sql) {
            sql.usersQueries.hasAnyUser().executeAsOne()
        }

    override suspend fun seed() {
        when (
            val result =
                authService.setupRoot(
                    RegisterRequest(
                        email = DEMO_EMAIL,
                        password = DEMO_PASSWORD,
                        displayName = DEMO_DISPLAY_NAME,
                    ),
                )
        ) {
            is AppResult.Success -> logger.info { "seed: demo root '$DEMO_EMAIL' created" }
            is AppResult.Failure -> logger.warn { "seed: demo root not created — ${result.error.code}" }
        }

        seedMember(MEMBER_EMAIL, MEMBER_DISPLAY_NAME)
        seedMember(RESTRICTED_EMAIL, RESTRICTED_DISPLAY_NAME)
        revokePermissions(RESTRICTED_EMAIL)
    }

    private suspend fun seedMember(
        email: String,
        displayName: String,
    ) {
        when (
            val result =
                authService.register(
                    RegisterRequest(email = email, password = DEMO_PASSWORD, displayName = displayName),
                )
        ) {
            is AppResult.Success -> logger.info { "seed: demo member '$email' created" }
            is AppResult.Failure -> logger.warn { "seed: demo member '$email' not created — ${result.error.code}" }
        }
    }

    private suspend fun revokePermissions(email: String) {
        val idOrNull =
            suspendTransaction(sql) {
                sql.usersQueries
                    .selectByEmailNormalized(email_normalized = email)
                    .executeAsOneOrNull()
                    ?.id
            }
        val id = idOrNull ?: return
        suspendTransaction(sql) {
            sql.usersQueries.updatePermissions(can_edit = 0L, can_share = 0L, id = id)
        }
    }

    companion object {
        const val DEMO_EMAIL = "demo@listenup.app"
        const val DEMO_PASSWORD = "demo-password"
        const val DEMO_DISPLAY_NAME = "Demo User"

        const val MEMBER_EMAIL = "member@listenup.app"
        const val MEMBER_DISPLAY_NAME = "Demo Member"

        const val RESTRICTED_EMAIL = "restricted@listenup.app"
        const val RESTRICTED_DISPLAY_NAME = "Restricted Member"
    }
}
