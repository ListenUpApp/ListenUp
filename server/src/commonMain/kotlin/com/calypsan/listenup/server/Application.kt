package com.calypsan.listenup.server

import com.calypsan.listenup.core.appCoroutineExceptionHandler
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.backup.MaintenanceState
import com.calypsan.listenup.server.auth.SocketTicketStore
import com.calypsan.listenup.server.plugins.installJwtAuth
import com.calypsan.listenup.server.plugins.installMaintenanceGate
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.ext.inject

internal val logger = KotlinLogging.logger("com.calypsan.listenup.server.Application")

internal const val SEED_PROFILE_DEMO = "demo"

/**
 * The server's long-lived background scope, built on [parent] (the Ktor application's context). Its
 * [SupervisorJob] has no parent: the scope is cancelled explicitly at `ApplicationStopped`
 * (`installGracefulShutdown`), so one failed child never cancels its siblings or the application.
 *
 * [appCoroutineExceptionHandler] is the net under every job launched here: on Kotlin/Native — the shipped
 * server — an exception that escapes a `launch` with no handler terminates the process. The handler logs it
 * at ERROR instead. It is a net, not a licence: background work still wraps its body (`launchNeverFatal`).
 */
internal fun applicationScopeOf(parent: CoroutineContext): CoroutineScope =
    CoroutineScope(parent + SupervisorJob() + appCoroutineExceptionHandler)

// Ktor requires an `Application` receiver on a module, and Application is a CoroutineScope; the module
// suspends for startup seeding, it does not launch work into that scope.
@Suppress("SuspendFunWithCoroutineScopeReceiver")
suspend fun Application.module() {
    installCorePlugins()

    val seedProfile = resolveSeedProfile()
    val applicationScope = applicationScopeOf(coroutineContext)
    val resolvedLibraryPaths =
        resolveLibraryPaths().ifEmpty {
            resolveDemoLibraryFallback(seedProfile)?.let { listOf(it) }.orEmpty()
        }
    val homeDir = resolveImageHome()
    acquireDataDirLockIfEnabled(homeDir)
    val metadataPrecedence = resolveMetadataPrecedence()
    val embeddedCoverCacheSize = resolveEmbeddedCoverCacheSize()
    val pushRelayUrl = resolvePushRelayUrl()
    val pushSenderToken = resolvePushSenderToken()
    val hardcoverClientId = resolveHardcoverClientId()
    val hardcoverApiBaseUrl = resolveHardcoverApiBaseUrl()

    installDependencies(
        seedProfile = seedProfile,
        applicationScope = applicationScope,
        homeDir = homeDir,
        metadataPrecedence = metadataPrecedence,
        embeddedCoverCacheSize = embeddedCoverCacheSize,
        watchEnabled = environment.config.watchEnabled(),
        pushRelayUrl = pushRelayUrl,
        pushSenderToken = pushSenderToken,
        hardcoverClientId = hardcoverClientId,
        hardcoverApiBaseUrl = hardcoverApiBaseUrl,
    )

    backfillPublicProfiles(this)
    backfillAdminUserRoster(this)
    launchSeeders(
        application = this,
        scope = applicationScope,
        seedProfile = seedProfile,
        libraryConfigured = resolvedLibraryPaths.isNotEmpty(),
    )

    installRequestPipeline()
    installMaintenanceGate(koinGet<MaintenanceState>())

    val jwt by inject<JwtConfiguration>()
    val sessions by inject<SessionService>()
    val socketTickets by inject<SocketTicketStore>()
    installJwtAuth(jwt, SessionLiveness(sessions::isLive), socketTickets)

    installAppRoutes(homeDir)

    startBackgroundTasks(applicationScope, resolvedLibraryPaths)
    installGracefulShutdown(applicationScope)
}
