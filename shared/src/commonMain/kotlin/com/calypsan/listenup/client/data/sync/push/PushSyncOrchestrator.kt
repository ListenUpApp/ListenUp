package com.calypsan.listenup.client.data.sync.push

import com.calypsan.listenup.client.core.error.ErrorBus
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.data.sync.SyncMutex
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.error.AppException

private val logger = KotlinLogging.logger {}

/**
 * Contract for push sync orchestration.
 * Enables testing without concrete implementation.
 */
interface PushSyncOrchestratorContract {
    val isFlushing: StateFlow<Boolean>

    suspend fun flush()
}

/**
 * Orchestrates pushing pending local operations to the server.
 *
 * Uses last-write-wins strategy: all operations are pushed regardless of
 * server state. The server timestamp updates to reflect the latest write.
 *
 * Auto-triggers flush when network connectivity is restored.
 */
class PushSyncOrchestrator(
    private val repository: PendingOperationRepositoryContract,
    private val executor: OperationExecutorContract,
    private val networkMonitor: NetworkMonitor,
    private val syncMutex: SyncMutex,
    private val scope: CoroutineScope,
) : PushSyncOrchestratorContract {
    // Override properties can't use explicit backing fields - must use traditional pattern
    private val _isFlushing = MutableStateFlow(false)
    override val isFlushing: StateFlow<Boolean> = _isFlushing.asStateFlow()

    init {
        // Auto-flush when connectivity restored
        // Mutex ensures SSE events don't interleave with push operations
        scope.launch {
            networkMonitor.isOnlineFlow
                .filter { it } // Only trigger on online=true
                .collect {
                    logger.debug { "Network restored - triggering push sync flush" }
                    syncMutex.withLock {
                        flush()
                    }
                }
        }

        // Auto-flush when new operation is queued (if online)
        // Mutex ensures SSE events don't interleave with push operations
        scope.launch {
            repository.newOperationQueued.collect {
                if (networkMonitor.isOnline()) {
                    logger.debug { "New operation queued - triggering push sync flush" }
                    syncMutex.withLock {
                        flush()
                    }
                } else {
                    logger.debug { "New operation queued but offline - will sync when connected" }
                }
            }
        }

        // Reset any stuck operations from previous session
        scope.launch {
            repository.resetStuckOperations()
        }
    }

    /**
     * Flush all pending operations to the server.
     *
     * Uses last-write-wins: all operations are pushed without conflict checking.
     * Failed operations are marked for user review after MAX_RETRIES.
     */
    override suspend fun flush() {
        logger.info { "🔄 PUSH SYNC: flush() called, isOnline=${networkMonitor.isOnline()}" }

        if (!networkMonitor.isOnline()) {
            logger.warn { "🔄 PUSH SYNC: Offline - skipping flush" }
            return
        }

        // Use tryLock to avoid TOCTOU race on _isFlushing
        if (!syncMutex.tryLock()) {
            logger.info { "🔄 PUSH SYNC: Already flushing - skipping" }
            return
        }

        _isFlushing.value = true
        logger.info { "🔄 PUSH SYNC: Starting flush..." }

        try {
            var processed = 0
            while (true) {
                val batch = repository.getNextBatch(limit = 50)
                logger.info { "🔄 PUSH SYNC: Got batch of ${batch.size} operations" }
                if (batch.isEmpty()) {
                    logger.info { "🔄 PUSH SYNC: No more operations to process" }
                    break
                }

                val ids = batch.map { it.id }
                logger.info { "🔄 PUSH SYNC: Processing operations: ${batch.map { "${it.operationType}:${it.id}" }}" }
                repository.markInProgress(ids)

                // Execute all operations (last-write-wins)
                val results = executor.execute(batch)

                // Process results
                val succeeded =
                    buildList {
                        results.forEach { (id, result) ->
                            if (result is Success) {
                                add(id)
                                processed++
                            } else {
                                val operation = batch.first { it.id == id }
                                handleFailure(
                                    operation,
                                    (result as? com.calypsan.listenup.client.core.Failure)?.let {
                                        AppException(it.error)
                                    },
                                )
                            }
                        }
                    }

                if (succeeded.isNotEmpty()) {
                    repository.markCompleted(succeeded)
                }

                // Check if we should continue (might have gone offline)
                if (!networkMonitor.isOnline()) {
                    logger.debug { "Network lost during flush - stopping" }
                    break
                }
            }

            if (processed > 0) {
                logger.info { "Push sync completed: $processed operation(s) synced" }
            } else {
                logger.debug { "Push sync completed: no operations to sync" }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorBus.emit(SyncError.PushFailed(debugInfo = e.message))
            logger.error(e) { "Push sync flush failed" }
        } finally {
            _isFlushing.value = false
            syncMutex.unlock()
        }
    }

    private suspend fun handleFailure(
        operation: com.calypsan.listenup.client.data.local.db.PendingOperationEntity,
        e: Exception?,
    ) {
        val newAttemptCount = operation.attemptCount + 1
        val error =
            if (newAttemptCount >= MAX_RETRIES) {
                "Max retries exceeded: ${e?.message ?: "Unknown error"}"
            } else {
                e?.message ?: "Unknown error"
            }
        repository.markFailed(operation.id, error)
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}
