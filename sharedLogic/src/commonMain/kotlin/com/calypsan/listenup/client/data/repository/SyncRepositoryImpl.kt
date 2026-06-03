package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.Success
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.core.suspendRunCatching
import com.calypsan.listenup.client.data.sync.ConnectionState
import com.calypsan.listenup.client.data.sync.SyncEngine
import com.calypsan.listenup.client.data.sync.SyncEngineState
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.playback.ListeningEventRecorder
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val logger = KotlinLogging.logger {}

/**
 * Bridges the domain sync facade to the renovated client sync engine.
 *
 * Also hosts orphan-span recovery: on the first successful [startEngineForCurrentUser]
 * call (i.e. first authenticated user startup), [ListeningEventRecorder.recoverOrphan] is
 * invoked once to promote any leftover tentative span from a crash into a proper
 * [com.calypsan.listenup.client.data.local.db.ListeningEventEntity]. Subsequent calls
 * (sync triggers, reconnects) skip recovery — the tentative_span table is a singleton and
 * will be empty after the first successful recovery.
 */
class SyncRepositoryImpl(
    private val syncEngine: SyncEngine,
    private val syncEngineState: SyncEngineState,
    private val authSession: AuthSession,
    private val listeningEventRecorder: ListeningEventRecorder,
    scope: CoroutineScope,
) : SyncRepository {
    /** Ensures [ListeningEventRecorder.recoverOrphan] runs at most once per process lifetime. */
    private var orphanRecovered = false
    override val syncState: StateFlow<SyncState> =
        syncEngineState
            .observe()
            .map { snapshot ->
                when (snapshot.connection) {
                    ConnectionState.Connecting -> {
                        SyncState.Syncing
                    }

                    is ConnectionState.Connected -> {
                        SyncState.Success(Timestamp(snapshot.lastSuccessAtMillis ?: currentEpochMilliseconds()))
                    }

                    is ConnectionState.Disconnected -> {
                        SyncState.Idle
                    }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = SyncState.Idle,
            )

    override val isServerScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val scanProgress: StateFlow<ScanProgressState?> = MutableStateFlow(null)

    override suspend fun sync(): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun connectRealtime() {
        startEngineForCurrentUser()
    }

    override suspend fun disconnect() {
        syncEngine.stopAndJoin()
    }

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun refreshListeningHistory(): AppResult<Unit> = Success(Unit)

    override suspend fun forceFullResync(): AppResult<Unit> = startEngineForCurrentUser()

    private suspend fun startEngineForCurrentUser(): AppResult<Unit> =
        suspendRunCatching {
            val userId = authSession.getUserId() ?: return@suspendRunCatching Unit
            syncEngine.start(userId)
            if (!orphanRecovered) {
                orphanRecovered = true
                try {
                    listeningEventRecorder.recoverOrphan()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Orphan recovery failure is non-fatal — log and continue.
                    // The orphan will remain and may be recovered on the next startup.
                    logger.warn(e) { "Orphan span recovery failed — will retry on next startup" }
                }
            }
        }
}
