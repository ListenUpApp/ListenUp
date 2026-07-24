package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AppError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Connection state for the sync firehose. */
internal sealed interface ConnectionState {
    /** Not connected. [reason] is null at engine start, populated on disconnect. */
    data class Disconnected(
        val reason: String?,
    ) : ConnectionState

    /** Connect attempt in flight. */
    data object Connecting : ConnectionState

    /** Open firehose stream. [lastEventId] is the latest revision processed (null until first event). */
    data class Connected(
        val lastEventId: Long?,
    ) : ConnectionState
}

/**
 * Snapshot of engine-wide state exposed to UI as a `Flow<EngineSnapshot>`.
 *
 * UI reads this for ambient indicators (connection chip, "offline since X" hints,
 * diagnostics screens). UI never mutates it — mutation is internal to the engine.
 *
 * [meaningfulErrorActive] is the threshold-gated "user, you should know" signal:
 * silence is appropriate for normal recovery, signal is appropriate for sustained
 * failure. UI consumes this; below threshold UI shows nothing.
 */
internal data class EngineSnapshot(
    val connection: ConnectionState = ConnectionState.Disconnected(reason = null),
    val recentErrorCount: Int = 0,
    val lastSuccessAtMillis: Long? = null,
    val pendingQueueDepth: Int = 0,
    /** Terminal ops that exhausted their retry budget — dead letters awaiting user retry/dismiss or age-GC. */
    val deadLetterCount: Int = 0,
    val meaningfulErrorActive: Boolean = false,
)

private const val ERROR_COUNT_THRESHOLD = 5
private const val SUCCESS_AGE_THRESHOLD_SECONDS = 60L

/**
 * Mutable state holder for [EngineSnapshot]. Engine components mutate via the
 * `setX` / `recordX` methods; UI reads via [value] / [observe].
 */
internal class SyncEngineState {
    private val flow = MutableStateFlow(EngineSnapshot())

    /** Current snapshot. */
    val value: EngineSnapshot get() = flow.value

    /** Observable for UI consumption. Hot, replays the latest snapshot. */
    fun observe(): StateFlow<EngineSnapshot> = flow.asStateFlow()

    /** Update the firehose connection state. */
    fun setConnection(state: ConnectionState) {
        flow.update { it.copy(connection = state) }
    }

    /** Update the pending-operation queue depth. */
    fun setQueueDepth(depth: Int) {
        flow.update { it.copy(pendingQueueDepth = depth) }
    }

    /** Update the dead-letter count. */
    fun setDeadLetterCount(count: Int) {
        flow.update { it.copy(deadLetterCount = count) }
    }

    /** Record a typed engine error; bumps [EngineSnapshot.recentErrorCount] and re-evaluates the threshold. */
    @Suppress("UnusedParameter")
    fun recordError(error: AppError) {
        flow.update {
            val newCount = it.recentErrorCount + 1
            it.copy(
                recentErrorCount = newCount,
                meaningfulErrorActive = it.meaningfulErrorActive || newCount >= ERROR_COUNT_THRESHOLD,
            )
        }
    }

    /** Record a successful interaction. Clears [EngineSnapshot.recentErrorCount] and refreshes [EngineSnapshot.lastSuccessAtMillis]. */
    fun recordSuccess(nowMillis: Long) {
        flow.update {
            it.copy(
                recentErrorCount = 0,
                lastSuccessAtMillis = nowMillis,
                meaningfulErrorActive = false,
            )
        }
    }

    /**
     * Re-evaluate [EngineSnapshot.meaningfulErrorActive] against the staleness threshold. Called
     * periodically by the engine (e.g. on reconnect attempts) so a long quiet
     * period without errors still surfaces as "something's wrong" to the UI.
     */
    fun evaluateMeaningfulError(nowMillis: Long) {
        flow.update {
            val lastSuccess = it.lastSuccessAtMillis ?: return@update it
            val ageSeconds = (nowMillis - lastSuccess) / 1000L
            if (ageSeconds >= SUCCESS_AGE_THRESHOLD_SECONDS) {
                it.copy(meaningfulErrorActive = true)
            } else {
                it
            }
        }
    }
}
