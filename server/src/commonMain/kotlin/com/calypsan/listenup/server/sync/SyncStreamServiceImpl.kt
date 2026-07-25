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
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.api.sync.TargetedMatch
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page

private val log = KotlinLogging.logger("com.calypsan.listenup.server.sync.SyncStreamService")

/** Production heartbeat cadence; clients bound their read-idle watchdog at 3× this interval. */
private const val HEARTBEAT_INTERVAL_MILLIS = 25_000L

/**
 * The firehose — [SyncStreamService] streaming the [ChangeBus] tail over the RPC socket:
 * replay-then-live ordering, per-user + per-row access gating (via the shared
 * [firehoseGateReason] chain), and cursor-stale detection with the attach-time re-check that
 * closes the eviction race.
 *
 * Delivery shape: `RpcEvent.Data(SyncFrame(domain, revision, json))`, one frame per bus event,
 * with an immediate [SyncControl.Heartbeat] hello on subscribe (the client latches Connected on
 * it), a heartbeat frame every 25s (the client's liveness signal), resume via the
 * `sinceRevision` parameter, and expected terminal conditions ([SyncControl.CursorStale]) as
 * frames + completion rather than errors. The C2 session-liveness gate lives in the
 * registration's `streamLiveness` predicate, not here.
 *
 * The [bookAccessPolicy] thunk is resolved only when a book-gated content event must be probed,
 * so harnesses driving only ungated domains need no policy.
 */
internal class SyncStreamServiceImpl(
    private val bus: ChangeBus,
    private val registry: SyncRegistry,
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
        SyncStreamServiceImpl(bus, registry, bookAccessPolicy, principal, heartbeatIntervalMillis)

    /**
     * One connection's frame stream: stale pre-check, hello, then the merged live tail
     * (data + control + heartbeat). The attach-time [CursorStaleAtAttach] marker thrown inside
     * [dataFrames] surfaces here as a terminal CursorStale frame — an expected condition,
     * never an error on the wire.
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
     * The [ChangeBus] data tail `(userId, role)` is entitled to see. Skips events at or below
     * [sinceRevision] (already-delivered replay), events for other users, and access-gated
     * content. The `onSubscription` re-check throws [CursorStaleAtAttach] when a burst evicted
     * past the cursor between the caller's pre-subscribe snapshot and the moment this collector
     * actually attaches.
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
        return busEvent.repo.toSyncFrame(busEvent.event)
    }

    /**
     * The bus's control channel scoped to [userId]: frames addressed to this subscriber plus
     * content-free BROADCAST frames, delivered as CONTROL [SyncFrame]s.
     */
    private fun controlFrames(userId: String): Flow<SyncFrame> =
        bus
            .subscribeControl()
            .filter { it.userId == userId || it.userId == ChangeBus.BROADCAST }
            .map { controlFrame(it.control) }

    /**
     * A [SyncControl.Heartbeat] CONTROL frame every [heartbeatIntervalMillis] ms — keeps
     * NAT/load-balancer paths warm and doubles as the client's liveness watchdog signal.
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

    override suspend fun pullDomain(
        domain: String,
        since: Long,
        limit: Int,
    ): AppResult<SyncPage> =
        withDomain(domain) { caller, typedRepo, extraWhere ->
            val page =
                typedRepo.pullSince(
                    caller.userId.value,
                    since,
                    limit.coerceIn(MIN_PAGE_LIMIT, MAX_PAGE_LIMIT),
                    extraWhere,
                )
            typedRepo.toSyncPage(domain, page)
        }

    override suspend fun pullByIds(
        domain: String,
        match: TargetedMatch,
        ids: List<String>,
    ): AppResult<SyncPage> {
        // Refuse rather than truncate: a short response is indistinguishable from "these ids are
        // gone" and would make the caller tombstone rows it can still reach.
        if (ids.size > MAX_TARGETED_IDS) {
            return AppResult.Failure(SyncError.TooManyIds(requested = ids.size, maxIds = MAX_TARGETED_IDS))
        }
        if (!match.isSupportedFor(domain)) {
            return AppResult.Failure(SyncError.UnsupportedMatch(domain = domain, match = match.name))
        }
        val distinct = ids.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return withDomain(domain) { caller, typedRepo, extraWhere ->
            val page =
                typedRepo.pullByIds(
                    caller.userId.value,
                    matchColumn = match.column,
                    matchValues = distinct,
                    extraWhere = extraWhere,
                )
            typedRepo.toSyncPage(domain, page)
        }
    }

    override suspend fun digest(
        domain: String,
        cursor: Long,
    ): AppResult<DomainDigest> =
        withDomain(domain) { caller, typedRepo, extraWhere ->
            typedRepo.digest(caller.userId.value, cursor, extraWhere)
        }

    override suspend fun listDomains(): AppResult<List<String>> =
        if (principal.current() == null) {
            AppResult.Failure(AuthError.PermissionDenied())
        } else {
            AppResult.Success(registry.knownDomains())
        }

    /**
     * Resolves the caller and the domain's repository, applies that domain's read-time access
     * filter, and runs [block]. Every pull entry point funnels through here so the access gate
     * cannot be forgotten on one of them.
     */
    private suspend fun <T> withDomain(
        domain: String,
        block: suspend (UserPrincipal, SyncableRepo<Any>, SqlFragment?) -> T,
    ): AppResult<T> {
        val caller = principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        val repo = registry.lookup(domain) ?: return AppResult.Failure(SyncError.UnknownDomain(domain = domain))

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepo<Any>
        val extraWhere = accessFilterFor(domain, caller.userId.value, caller.role) { bookAccessPolicy() }
        return AppResult.Success(block(caller, typedRepo, extraWhere))
    }

    /**
     * Encodes a page into the wire shape: envelope typed, each row its own encoded string.
     *
     * One string per row (rather than one blob per page) keeps the client from holding a
     * whole-page JSON tree alongside the decoded rows, and lets a single malformed row fail on
     * its own instead of taking the page with it.
     */
    private fun SyncableRepo<Any>.toSyncPage(
        domain: String,
        page: Page<Any>,
    ): SyncPage =
        SyncPage(
            domain = domain,
            items = page.items.map { encodeItemAsJson(it) },
            nextCursor = page.nextCursor,
            hasMore = page.hasMore,
        )
}

/**
 * Internal marker thrown from the attach-time staleness re-check inside the merged data tail. A
 * plain (non-cancellation) exception so it propagates out of `merge` to the enclosing `catch`,
 * which folds it into a terminal [SyncControl.CursorStale] frame — it never crosses the wire.
 */
private class CursorStaleAtAttach(
    val floor: Long,
) : Exception("RPC firehose cursor stale at ChangeBus subscription attach")

/**
 * Public construction seam for the RPC firehose, mirroring the `createBookService` idiom: the
 * impl stays `internal`, cross-module test harnesses (the client-engine e2e fixtures in
 * `:app:sharedLogic`) mount the service through this factory, scoped to a per-connection [principal]
 * exactly as the production RPC registration does via `copyWith`.
 */
fun createSyncStreamService(
    bus: ChangeBus,
    registry: SyncRegistry,
    bookAccessPolicy: () -> BookAccessPolicy,
    principal: PrincipalProvider,
): SyncStreamService =
    SyncStreamServiceImpl(
        bus = bus,
        registry = registry,
        bookAccessPolicy = bookAccessPolicy,
        principal = principal,
    )
