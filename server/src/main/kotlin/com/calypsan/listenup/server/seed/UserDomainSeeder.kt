package com.calypsan.listenup.server.seed

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.db.UserEntity
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

private val logger = KotlinLogging.logger {}

/**
 * Seeds a single demo user — a known account so a developer running the demo server
 * can log straight in. Created through [AuthServiceImpl.setupRoot] (the real auth
 * write-path), which yields a ROOT, ACTIVE user.
 *
 * Demo credentials are intentionally well-known and documented; the demo profile is
 * never for production use.
 */
class UserDomainSeeder(
    private val db: Database,
    private val authService: AuthServiceImpl,
) : DomainSeeder {
    override val domainName: String = "user"
    override val order: Int = 0

    override suspend fun isAlreadySeeded(): Boolean =
        suspendTransaction(db) { !UserEntity.all().limit(1).empty() }

    override suspend fun seed() {
        val result =
            authService.setupRoot(
                RegisterRequest(
                    email = DEMO_EMAIL,
                    password = DEMO_PASSWORD,
                    displayName = DEMO_DISPLAY_NAME,
                ),
            )
        when (result) {
            is AppResult.Success -> logger.info { "seed: demo user '$DEMO_EMAIL' created" }
            is AppResult.Failure ->
                logger.warn { "seed: demo user not created — ${result.error.code}" }
        }
    }

    companion object {
        const val DEMO_EMAIL = "demo@listenup.app"
        const val DEMO_PASSWORD = "demo-password"
        const val DEMO_DISPLAY_NAME = "Demo User"
    }
}
