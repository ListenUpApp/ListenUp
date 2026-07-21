package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription

private val log = KotlinLogging.logger("com.calypsan.listenup.server.sync.SyncStreamService")

/** Production heartbeat cadence — the RPC equivalent of the SSE `:keepalive` interval. */
private const val HEARTBEAT_INTERVAL_MILLIS = 25_000L

/**
 * The RPC firehose — [SyncStreamService] over the same [ChangeBus] the SSE route streams from,
 * a faithful port of `streamFirehose`/`collectFirehoseEvents` delivery semantics: replay-then-live
 * ordering, per-user + per-row access gating (via the shared [firehoseGateReason] chain), and
 * cursor-stale detection with the attach-time re-check that closes the eviction race.
 *
 * Where the SSE path writes `event:`/`id:`/`data:` lines, this emits
 * `RpcEvent.Data(SyncFrame(domain, revision, json))` using the same serializers. Differences by
 * design: an immediate [SyncControl.Heartbeat] hello on subscribe (the client latches Connected on
 * it), a heartbeat frame every 25s (replacing the `:keepalive` comment), resume via the
 * `sinceRevision` parameter (was `Last-Event-ID`), and expected terminal conditions
 * ([SyncControl.CursorStale]) as frames + completion rather than errors. The C2 session-liveness
 * gate lives in the registration's `streamLiveness` predicate, not here.
 *
 * The [bookAccessPolicy] thunk mirrors the SSE route: it is resolved only when a book-gated
 * content event must be probed, so harnesses driving only ungated domains need no policy.
 */
internal class SyncStreamServiceImpl(
    private val bus: ChangeBus,
    private val bookAccessPolicy: () -> BookAccessPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val heartbeatIntervalMillis: Long = HEARTBEAT_INTERVAL_MILLIS,
) : SyncStreamService {
    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> =
        flow {
            // Fail closed: without a caller, per-user events cannot be safely filtered — refuse
            // the stream with the same typed denial every unscoped service call gets.
            val caller = principal.current()
            if (caller == null) {
                emit(RpcEvent.Error(AuthError.PermissionDenied()))
                return@flow
            }
            emitAll(frames(caller, sinceRevision).map<SyncFrame, RpcEvent<SyncFrame>> { RpcEvent.Data(it) })
        }

    /** Returns a copy scoped to [principal]. The RPC mount calls this per-connection. */
    fun copyWith(principal: PrincipalProvider): SyncStreamServiceImpl =
        SyncStreamServiceImpl(bus, bookAccessPolicy, principal, heartbeatIntervalMillis)

    /**
     * One connection's frame stream: stale pre-check, hello, then the merged live tail
     * (data + control + heartbeat). The attach-time [CursorStaleAtAttach] marker thrown inside
     * [dataFrames] surfaces here as a terminal CursorStale frame — an expected condition, never
     * an error on the wire.
     */
    private fun frames(
        caller: UserPrincipal,
        sinceRevision: Long?,
    ): Flow<SyncFrame> =
        flow {
            // Fast-path pre-check: reject an already-stale cursor before the side-streams spin
            // up. [dataFrames] re-runs the same check at actual subscription attach, closing the
            // race window between this snapshot and that attach.
            staleCursorFloor(bus, sinceRevision)?.let { floor ->
                log.debug {
                    "rpc sync stream cursor stale: userId=${caller.userId.value} " +
                        "sinceRevision=$sinceRevision oldestRetained=$floor; sending CursorStale"
                }
                emit(controlFrame(SyncControl.CursorStale(lastKnownRevision = floor)))
                return@flow
            }
            log.info { "rpc sync stream opened: userId=${caller.userId.value}" }
            emit(HELLO_FRAME)
            emitAll(
                merge(
                    dataFrames(caller.userId.value, caller.role, sinceRevision),
                    controlFrames(caller.userId.value),
                    heartbeatFrames(),
                ),
            )
        }.catch { e ->
            if (e is CursorStaleAtAttach) {
                emit(controlFrame(SyncControl.CursorStale(lastKnownRevision = e.floor)))
            } else {
                throw e
            }
        }.onCompletion { log.info { "rpc sync stream closed: userId=${caller.userId.value}" } }

    /**
     * The [ChangeBus] data tail `(userId, role)` is entitled to see — the port of
     * `collectFirehoseEvents`. Skips events at or below [sinceRevision] (already-delivered
     * replay), events for other users, and access-gated content. The `onSubscription` re-check
     * throws [CursorStaleAtAttach] when a burst evicted past the cursor between the caller's
     * pre-subscribe snapshot and the moment this collector actually attaches.
     */
    private fun dataFrames(
        userId: String,
        role: UserRole,
        sinceRevision: Long?,
    ): Flow<SyncFrame> =
        bus
            .subscribe()
            .onSubscription {
                staleCursorFloor(bus, sinceRevision)?.let { throw CursorStaleAtAttach(it) }
            }.filter { it.event.revision > (sinceRevision ?: 0L) }
            .mapNotNull { busEvent -> frameFor(busEvent, userId, role) }

    /** [busEvent] as a wire frame, or null when it is scoped to another user or access-gated. */
    private suspend fun frameFor(
        busEvent: BusEvent<*>,
        userId: String,
        role: UserRole,
    ): SyncFrame? {
        // Per-user scoping: a BusEvent carrying a userId belongs to a user-scoped domain —
        // deliver it only to that user. A null userId is a global-domain event.
        if (busEvent.userId != null && busEvent.userId != userId) return null
        val gatedReason = firehoseGateReason(busEvent, userId, role, bookAccessPolicy)
        if (gatedReason != null) {
            log.trace {
                "rpc firehose gated: domain=${busEvent.repo.domainName} " +
                    "event=${busEvent.event::class.simpleName} userId=$userId reason=$gatedReason"
            }
            return null
        }
        return SyncFrame(
            domain = busEvent.repo.domainName,
            revision = busEvent.event.revision,
            json = busEvent.repo.encodeSyncEventAsJson(busEvent.event),
        )
    }

    /**
     * The bus's control channel scoped to [userId]: frames addressed to this subscriber plus
     * content-free BROADCAST frames, delivered as CONTROL [SyncFrame]s — the port of the SSE
     * `event: control` side-job.
     */
    private fun controlFrames(userId: String): Flow<SyncFrame> =
        bus
            .subscribeControl()
            .filter { it.userId == userId || it.userId == ChangeBus.BROADCAST }
            .map { controlFrame(it.control) }

    /**
     * A [SyncControl.Heartbeat] CONTROL frame every [heartbeatIntervalMillis] ms — the RPC
     * replacement for the SSE `:keepalive` comment, doubling as the client's liveness watchdog.
     */
    private fun heartbeatFrames(): Flow<SyncFrame> =
        flow {
            while (true) {
                delay(heartbeatIntervalMillis)
                emit(HELLO_FRAME)
            }
        }

    private companion object {
        /** The stream-open hello and every subsequent heartbeat — one constant frame. */
        val HELLO_FRAME = controlFrame(SyncControl.Heartbeat)

        fun controlFrame(control: SyncControl): SyncFrame =
            SyncFrame(
                domain = SyncFrame.CONTROL,
                revision = null,
                json = contractJson.encodeToString(SyncControl.serializer(), control),
            )
    }
}

/**
 * Internal marker thrown from the attach-time staleness re-check inside the merged data tail. A
 * plain (non-cancellation) exception so it propagates out of `merge` to the enclosing `catch`,
 * which folds it into a terminal [SyncControl.CursorStale] frame — it never crosses the wire.
 */
private class CursorStaleAtAttach(
    val floor: Long,
) : Exception("RPC firehose cursor stale at ChangeBus subscription attach")
