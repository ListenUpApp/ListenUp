package com.calypsan.listenup.server

import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installMaintenanceGate
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.ext.inject

internal val logger = KotlinLogging.logger("com.calypsan.listenup.server.Application")

internal const val SEED_PROFILE_DEMO = "demo"

fun Application.module() {
    installCorePlugins()

    val seedProfile = resolveSeedProfile()
    val applicationScope = CoroutineScope(coroutineContext + SupervisorJob())
    val resolvedLibraryPaths =
        resolveLibraryPaths().ifEmpty {
            resolveDemoLibraryFallback(seedProfile)?.let { listOf(it) } ?: emptyList()
        }
    val homeDir = resolveImageHome()
    acquireDataDirLockIfEnabled(homeDir)
    val metadataPrecedence = resolveMetadataPrecedence()
    val embeddedCoverCacheSize = resolveEmbeddedCoverCacheSize()
    val pushRelayUrl = resolvePushRelayUrl()

    installDependencies(
        seedProfile,
        applicationScope,
        homeDir,
        metadataPrecedence,
        embeddedCoverCacheSize,
        environment.config.watchEnabled(),
        pushRelayUrl,
    )

    backfillPublicProfiles()
    backfillAdminUserRoster()
    launchSeeders(applicationScope, seedProfile, resolvedLibraryPaths.isNotEmpty())

    installRequestPipeline()
    installMaintenanceGate(koinGet<MaintenanceState>())

    val jwt by inject<JwtConfiguration>()
    val sessions by inject<SessionService>()
    installJwtAuth(jwt, sessions::isLive)

    installAppRoutes(homeDir)

    startBackgroundTasks(applicationScope, resolvedLibraryPaths)
    installGracefulShutdown(applicationScope)
}
