package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.auth.PepperedHasher
import com.calypsan.listenup.server.auth.ResetCodeGenerator
import com.calypsan.listenup.server.auth.RootResetToken
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.auth.resolveServerSecrets
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.resolveRootResetToken
import com.calypsan.listenup.server.scheduler.ExpiredPasswordResetCleanupTask
import com.calypsan.listenup.server.services.PasswordResetService
import com.calypsan.listenup.server.services.RootPasswordResetService
import io.ktor.server.config.ApplicationConfig
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Koin module wiring the password-reset slice — split out of [authModule] to keep that module
 * under the length budget ("split per feature, not monolithic"). Loaded alongside [authModule]
 * in the same Koin container ([com.calypsan.listenup.server.ApplicationPlugins]), so its
 * bindings resolve the auth-slice primitives ([ListenUpDatabase], [SessionService], `Clock`,
 * `Argon2Limiter`) that [authModule] declares.
 */
fun passwordResetModule(config: ApplicationConfig): Module {
    val secrets = resolveServerSecrets(config)
    return module {
        // Password-reset ticket/claim/code hashing. Keyed with the SAME server pepper as
        // RefreshTokenHasher (authModule) — see PasswordResetService's CODE_DOMAIN/CLAIM_DOMAIN/
        // TICKET_DOMAIN tags for how one pepper stays safe to reuse across contexts.
        single {
            PepperedHasher(pepper = secrets.refreshPepper.encodeToByteArray())
        }
        single { ResetCodeGenerator() }

        single {
            PasswordResetService(
                db = get<ListenUpDatabase>(),
                hasher = get(),
                codes = get(),
                clock = get(),
                // Narrowed to the one method PasswordResetService needs — the shared SessionService
                // singleton itself is the source of truth for session revocation.
                sessions = get<SessionService>()::revokeAll,
                passwords = get(),
            )
        }

        single { ExpiredPasswordResetCleanupTask(db = get<ListenUpDatabase>(), clock = get()) }

        // ⛔ MUST be `single`, never `factory`. A factory would mint a fresh token per injection,
        // so the value printed at startup — the first resolution, forced eagerly at boot by
        // `Application.rpcServiceBundle()`'s `koinGet<AuthServiceImpl>()`, which transitively
        // resolves this through RootPasswordResetService — would never match the one
        // AuthServiceImpl checks on every subsequent call. The hatch would be permanently,
        // silently dead. See the KDoc on [RootResetToken] for why that failure is
        // indistinguishable from "wrong token".
        single { resolveRootResetToken(clock = get()) }

        single {
            RootPasswordResetService(
                db = get<ListenUpDatabase>(),
                passwords = get(),
                // Narrowed to the one method RootPasswordResetService needs — same shape as
                // PasswordResetService's `sessions` binding above.
                sessions = get<SessionService>()::revokeAll,
                clock = get(),
                rootResetToken = get<RootResetToken>(),
            )
        }
    }
}
