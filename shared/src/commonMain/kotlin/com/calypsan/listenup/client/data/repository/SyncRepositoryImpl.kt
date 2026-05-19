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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Bridges the domain sync facade to the renovated client sync engine. */
class SyncRepositoryImpl(
    private val syncEngine: SyncEngine,
    private val syncEngineState: SyncEngineState,
    private val authSession: AuthSession,
    scope: CoroutineScope,
) : SyncRepository {
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

    override suspend fun resetForNewLibrary(newLibraryId: String): AppResult<Unit> = startEngineForCurrentUser()

    override suspend fun refreshListeningHistory(): AppResult<Unit> = Success(Unit)

    override suspend fun forceFullResync(): AppResult<Unit> = startEngineForCurrentUser()

    private suspend fun startEngineForCurrentUser(): AppResult<Unit> =
        suspendRunCatching {
            val userId = authSession.getUserId() ?: return@suspendRunCatching Unit
            syncEngine.start(userId)
        }
}
