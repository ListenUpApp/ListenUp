package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.sync.ActivitySyncPayload
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.SessionLiveness
import com.calypsan.listenup.server.plugins.isClientDisconnect
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.uuid.Uuid
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.ktor.ext.inject

private val log = KotlinLogging.logger("com.calypsan.listenup.server.sync.SyncFirehose")

// SSE `event:` line for out-of-band SyncControl frames (CursorStale, StreamError).
// Distinct from the per-domain `event: <domainName>` lines used for SyncEvent payloads.
private const val SSE_EVENT_CONTROL = "control"

// Cadence for the firehose session-liveness re-check (C2). The JWT auth wall only verifies liveness
// at the SSE UPGRADE, so once a firehose is live, revoking the session leaves it streaming until the
// socket drops; this poll severs a revoked connection within ~30s. Tests pass a shorter interval.
private const val SSE_LIVENESS_POLL_MILLIS = 30_000L

// The access-gated domains: their catch-up + digest are scoped through BookAccessPolicy.
// Every other domain passes a null filter (unchanged behaviour).
private const val BOOKS_DOMAIN = "books"
private const val COLLECTIONS_DOMAIN = "collections"

// Book-gated but GLOBAL (not per-user): a row with a non-null book_id is visible iff the caller can
// access that book; book_id IS NULL rows (e.g. user_joined) are public. Unlike books (`id IN
// (accessibleBooks)`) the gate is on the row's book_id, so the access subquery selects visible
// ACTIVITY ids, not book ids. ROOT/ADMIN are unconstrained (null filter).
private const val ACTIVITIES_DOMAIN = "activities"

// Wire domain stays "collection_shares" while the storage table is collection_grants — a USER grant
// maps to a share on the wire. Do NOT rename to "collection_grants" without a coordinated client
// migration (it would orphan client sync cursors). See CollectionGrantRepository.
private const val COLLECTION_SHARES_DOMAIN = "collection_shares"
private const val COLLECTION_BOOKS_DOMAIN = "collection_books"

// Cap on a single targeted `?ids=` / `?collectionIds=` / `?bookIds=` fetch (the scoped AccessChanged
// delta). The client chunks larger scopes into ≤ this many ids per request; the server rejects an
// over-cap request rather than silently truncating — a truncated response would look to the
// client like "these ids are no longer accessible" and wrongly tombstone them.
private const val MAX_TARGETED_IDS = 100

// Domains whose rows carry a `book_id` column, so a targeted `?bookIds=` fetch (the activities half
// of the scoped AccessChanged delta) can match on it. `matchColumn` is code-controlled, never user
// input — and honoring `?bookIds=` for a domain WITHOUT a `book_id` column would be a SQL error — so
// a `?bookIds=` request naming any other domain is a 400, not a query against a phantom column.
private val BOOK_ID_MATCH_DOMAINS = setOf(ACTIVITIES_DOMAIN)

// Domains whose rows carry a `collection_id` column, so a targeted `?collectionIds=` fetch (the
// collection-membership half of the scoped AccessChanged delta) can match on it — the sibling
// allowlist to BOOK_ID_MATCH_DOMAINS above. `collection_grants` (wire "collection_shares") also has
// a `collection_id` column and a wired driver, so it would not SQL-error, but no client caller
// targets it today and this route's own contract is `collectionIds` → `collection_books` only,
// so the allowlist stays scoped to that one domain rather than growing to match every column that
// happens to exist.
private val COLLECTION_ID_MATCH_DOMAINS = setOf(COLLECTION_BOOKS_DOMAIN)

// Admin-only domain: a row carries an absolute server filesystem path (operator disk
// topology), which members must never see. Unlike the per-row book/collection gates, this
// is whole-domain by role — members hold no folder rows at all, so there is nothing for them
// to reconcile and tombstones need not pass through.
private const val LIBRARY_FOLDERS_DOMAIN = "library_folders"

// Splices into `id IN (...)` to yield no rows — hides the library_folders domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
private val LIBRARY_FOLDERS_HIDDEN =
    SqlFragment(sql = "SELECT id FROM library_folders WHERE 1 = 0", args = emptyList())

// Admin-only domain: a row carries a user's email/role/status, which non-admins must never
// see. Whole-domain by role, same shape as LIBRARY_FOLDERS_DOMAIN above — members hold no
// roster rows at all, so there is nothing for them to reconcile and tombstones need not pass
// through.
private const val ADMIN_USER_ROSTER_DOMAIN = "admin_user_roster"

// Splices into `id IN (...)` to yield no rows — hides the admin_user_roster domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
private val ADMIN_USER_ROSTER_HIDDEN =
    SqlFragment(sql = "SELECT id FROM admin_user_roster WHERE 1 = 0", args = emptyList())

private fun isAdmin(role: UserRole): Boolean = role == UserRole.ROOT || role == UserRole.ADMIN

/**
 * How a wire domain's sync catch-up/digest is access-filtered — declared **data**, not control
 * flow. [ACCESS_FILTERS] maps each gated domain to its spec and [accessFilterFor] is a lookup, so
 * the per-row-vs-role-gated classification is a value a test can read directly (via
 * [perRowAccessGatedSyncDomains] / [roleGatedSyncDomains]) rather than parse out of a `when`.
 */
private sealed interface AccessFilterSpec {
    /**
     * `true` when a member sees a *subset* of the domain's rows through a per-row [BookAccessPolicy]
     * gate — the domains that oblige a matching client `AccessGate`. `false` for a whole-domain
     * role gate, whose members hold no rows at all and so need no client gate.
     */
    val perRowGated: Boolean

    /** The access subquery to splice as `id IN (…)`, or `null` when the caller is unconstrained (admin / sees-all). */
    fun fragment(
        userId: String,
        role: UserRole,
        policy: () -> BookAccessPolicy,
    ): SqlFragment?

    /**
     * A per-row gate: the visible id set is produced from [BookAccessPolicy] by [produce] — the
     * domain's single visibility rule. The [policy] thunk is resolved here (only for a gated
     * domain), never for an ungated one.
     */
    class PerRow(
        private val produce: (BookAccessPolicy, String, UserRole) -> SqlFragment?,
    ) : AccessFilterSpec {
        override val perRowGated: Boolean = true

        override fun fragment(
            userId: String,
            role: UserRole,
            policy: () -> BookAccessPolicy,
        ): SqlFragment? = produce(policy(), userId, role)
    }

    /**
     * A whole-domain role gate: non-admins get [hidden] (a subquery yielding no rows), admins get
     * `null` (no filter). Never touches [BookAccessPolicy] — the row content, not the caller's
     * access, is what makes it admin-only.
     */
    class RoleGatedHide(
        private val hidden: SqlFragment,
    ) : AccessFilterSpec {
        override val perRowGated: Boolean = false

        override fun fragment(
            userId: String,
            role: UserRole,
            policy: () -> BookAccessPolicy,
        ): SqlFragment? = if (isAdmin(role)) null else hidden
    }
}

/**
 * The declared access-filter catalog: every gated wire domain → how its catch-up/digest filter is
 * produced. A field rename in any visibility predicate ripples through this single map, not
 * per-route. An ungated domain is simply absent — the lookup returns `null` (no filter).
 */
private val ACCESS_FILTERS: Map<String, AccessFilterSpec> =
    mapOf(
        BOOKS_DOMAIN to AccessFilterSpec.PerRow { policy, userId, role -> policy.accessibleBookIdsSql(userId, role) },
        ACTIVITIES_DOMAIN to
            AccessFilterSpec.PerRow { policy, userId, role -> activitiesAccessFilter(policy, userId, role) },
        COLLECTIONS_DOMAIN to
            AccessFilterSpec.PerRow { policy, userId, role -> policy.accessibleCollectionIdsSql(userId, role) },
        COLLECTION_SHARES_DOMAIN to
            AccessFilterSpec.PerRow { policy, userId, role -> policy.visibleCollectionGrantIdsSql(userId, role) },
        COLLECTION_BOOKS_DOMAIN to
            AccessFilterSpec.PerRow { policy, userId, role -> policy.accessibleCollectionBookIdsSql(userId, role) },
        LIBRARY_FOLDERS_DOMAIN to AccessFilterSpec.RoleGatedHide(LIBRARY_FOLDERS_HIDDEN),
        ADMIN_USER_ROSTER_DOMAIN to AccessFilterSpec.RoleGatedHide(ADMIN_USER_ROSTER_HIDDEN),
    )

/**
 * The wire domain names whose sync catch-up/digest is access-filtered **per row** — exactly the
 * domains that oblige a client-side `AccessGate`. Read at runtime by `AccessGateParitySpec`, which
 * asserts this set equals the client catalog's gated domains — a data comparison, no source parsing.
 */
val perRowAccessGatedSyncDomains: Set<String>
    get() = ACCESS_FILTERS.filterValues { it.perRowGated }.keys

/**
 * The wire domain names hidden wholesale from non-admins by role — whole-domain gates that hold
 * no member rows and so need no client `AccessGate` (`AccessGateParitySpec`'s conscious-edit
 * exempt set).
 */
val roleGatedSyncDomains: Set<String>
    get() = ACCESS_FILTERS.filterValues { !it.perRowGated }.keys

/**
 * The access filter for [domainName]'s catch-up/digest, scoped to `(userId, role)` — or `null`
 * for an ungated domain (or an admin, who sees all). A pure lookup into [ACCESS_FILTERS].
 *
 * [policy] is a thunk, resolved only for a per-row gated domain: an ungated (absent) or role-gated
 * domain never touches it, so harnesses that drive only such domains need not register a
 * [BookAccessPolicy] (mirrors the firehose thunk).
 */
private fun accessFilterFor(
    domainName: String,
    userId: String,
    role: UserRole,
    policy: () -> BookAccessPolicy,
): SqlFragment? = ACCESS_FILTERS[domainName]?.fragment(userId, role, policy)

/**
 * Parses a targeted `?ids=` / `?collectionIds=` CSV into a de-duplicated id list, or `null` if it
 * exceeds [MAX_TARGETED_IDS] (the caller turns that into a `400`). Blank segments are dropped so a
 * trailing comma or an empty param yields an empty list rather than a phantom `""` id.
 */
private fun parseTargetedIds(raw: String): List<String>? {
    val ids =
        raw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    return if (ids.size > MAX_TARGETED_IDS) null else ids
}

/**
 * The `activities` access fragment: selects the visible ACTIVITY ids — a row is visible iff its
 * `book_id` is null (public) or accessible. Returns null for ROOT/ADMIN (unconstrained). Extracted
 * so the catch-up/digest override, the firehose gate's sibling logic, and their tests all share one
 * visibility definition. The wrapped subquery is code-controlled text; the caller's ids ride in
 * [SqlFragment.args], order preserved.
 */
internal fun activitiesAccessFilter(
    policy: BookAccessPolicy,
    userId: String,
    role: UserRole,
): SqlFragment? =
    policy.accessibleBookIdsSql(userId, role)?.let { bookAccess ->
        SqlFragment(
            sql =
                "SELECT a2.id FROM activities a2 " +
                    "WHERE a2.book_id IS NULL OR a2.book_id IN (${bookAccess.sql})",
            args = bookAccess.args,
        )
    }

/**
 * Mounts the Sync Foundation REST endpoints under `/api/v1/sync`:
 *  - `GET /api/v1/sync/events` — SSE firehose with `Last-Event-Id` resume
 *  - `GET /api/v1/sync/<domain>?since=<rev>&limit=<n>` — paginated catch-up
 *  - `GET /api/v1/sync/<domain>/digest?cursor=<rev>` — drift detection
 *  - `GET /api/v1/sync/domains` — registered domain list
 *
 * The firehose emits a comment-line keepalive every [heartbeatIntervalMillis]
 * milliseconds (25s by default) to prevent NAT/load-balancer drops on idle
 * connections. Tests pass a much shorter interval to keep the suite fast.
 */
fun Route.syncRoutes(
    heartbeatIntervalMillis: Long = 25_000L,
    // Session-liveness probe for the C2 firehose gate. Null (the default) leaves the gate inert — the
    // JWT auth wall still verifies liveness at the UPGRADE; production passes the real probe so a
    // revoked session's LIVE firehose is severed too. Tests pair it with a short [livenessPollMillis].
    sessionLiveness: SessionLiveness? = null,
    livenessPollMillis: Long = SSE_LIVENESS_POLL_MILLIS,
) {
    val bus by inject<ChangeBus>()
    val registry by inject<SyncRegistry>()
    val bookAccessPolicy by inject<BookAccessPolicy>()

    // SSE firehose — streams every domain's BusEvents in real time. The policy is passed as
    // a thunk, not the resolved instance: the firehose touches it only when it must gate a
    // books content event, so harnesses that never drive the books domain through the
    // firehose (e.g. the cross-module sync-engine E2E) need not register a BookAccessPolicy.
    sse("/api/v1/sync/events") {
        streamFirehose(bus, { bookAccessPolicy }, sessionLiveness, heartbeatIntervalMillis, livenessPollMillis)
    }

    get("/api/v1/sync/{domain}") {
        val principal =
            call.userPrincipalOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val userId = principal.userId.value
        val domainName =
            call.parameters["domain"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing domain")

        val repo =
            registry.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        // Books and the three collection domains are access-gated; every other domain passes
        // null (unchanged behaviour). For admins the policy returns null → no filter.
        val extraWhere = accessFilterFor(domainName, userId, principal.role) { bookAccessPolicy }

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepo<Any>

        // Targeted scoped-delta fetch: `?ids=` matches the row's own id (books, collections),
        // `?collectionIds=` matches its `collection_id` (collection_books), `?bookIds=` matches its
        // `book_id` (activities only — the allowlist keeps `matchColumn` sound). All three are access-
        // filtered, capped, and un-paged. Absent all three, fall back to the `?since=` catch-up page.
        val idsParam = call.request.queryParameters["ids"]
        val collectionIdsParam = call.request.queryParameters["collectionIds"]
        val bookIdsParam = call.request.queryParameters["bookIds"]

        val page: Page<Any> =
            when {
                idsParam != null -> {
                    parseTargetedIds(idsParam)?.let { ids ->
                        typedRepo.pullByIds(userId, matchColumn = "id", matchValues = ids, extraWhere = extraWhere)
                    } ?: return@get call.respond(HttpStatusCode.BadRequest, "too many ids (max $MAX_TARGETED_IDS)")
                }

                collectionIdsParam != null -> {
                    if (domainName !in COLLECTION_ID_MATCH_DOMAINS) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "collectionIds fetch not supported for domain: $domainName",
                        )
                    }
                    parseTargetedIds(collectionIdsParam)?.let { ids ->
                        typedRepo.pullByIds(
                            userId,
                            matchColumn = "collection_id",
                            matchValues = ids,
                            extraWhere = extraWhere,
                        )
                    } ?: return@get call.respond(HttpStatusCode.BadRequest, "too many ids (max $MAX_TARGETED_IDS)")
                }

                bookIdsParam != null -> {
                    if (domainName !in BOOK_ID_MATCH_DOMAINS) {
                        return@get call.respond(
                            HttpStatusCode.BadRequest,
                            "bookIds fetch not supported for domain: $domainName",
                        )
                    }
                    parseTargetedIds(bookIdsParam)?.let { ids ->
                        typedRepo.pullByIds(
                            userId,
                            matchColumn = "book_id",
                            matchValues = ids,
                            extraWhere = extraWhere,
                        )
                    } ?: return@get call.respond(HttpStatusCode.BadRequest, "too many ids (max $MAX_TARGETED_IDS)")
                }

                else -> {
                    val since =
                        call.request.queryParameters["since"]?.toLongOrNull()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "since must be a Long")
                    val limit =
                        call.request.queryParameters["limit"]
                            ?.toIntOrNull()
                            ?.coerceIn(1, 5000) ?: 500
                    typedRepo.pullSince(userId, since, limit, extraWhere)
                }
            }
        log.debug { "sync pull: domain=$domainName → ${page.items.size} rows userId=$userId" }
        // call.respond(page) would fail at runtime: kotlinx.serialization cannot
        // infer the concrete element serializer from the type-erased Page<Any>.
        // encodePageAsJson uses the concrete KSerializer<T> each repository provides.
        call.respondText(typedRepo.encodePageAsJson(page), ContentType.Application.Json)
    }

    get("/api/v1/sync/{domain}/digest") {
        val principal =
            call.userPrincipalOrNull()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val userId = principal.userId.value
        val domainName =
            call.parameters["domain"]
                ?: return@get call.respond(HttpStatusCode.BadRequest, "missing domain")
        val cursor =
            call.request.queryParameters["cursor"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "cursor must be a Long")
        val repo =
            registry.lookup(domainName)
                ?: return@get call.respond(HttpStatusCode.NotFound, "unknown domain: $domainName")

        val extraWhere = accessFilterFor(domainName, userId, principal.role) { bookAccessPolicy }

        @Suppress("UNCHECKED_CAST")
        val typedRepo = repo as SyncableRepo<Any>
        val digest: DomainDigest = typedRepo.digest(userId, cursor, extraWhere)
        log.debug { "sync digest: domain=$domainName cursor=$cursor → ${digest.count} rows userId=$userId" }
        call.respond(digest)
    }

    get("/api/v1/sync/domains") {
        call.respond(DomainList(domains = registry.knownDomains()))
    }
}

/**
 * Drives the SSE firehose for one connection: resolves the caller, applies
 * `Last-Event-Id` resume/cursor-stale handling, runs the keepalive side-job,
 * and streams [BusEvent]s the caller is entitled to see.
 *
 * The `event:` line carries the domain name; the `id:` line carries the
 * revision so a reconnecting client can resume via `Last-Event-Id`. A client
 * cursor older than the bus's replay-buffer floor — or a non-numeric one —
 * yields a [SyncControl.CursorStale] frame and closes, forcing REST catch-up.
 */
private suspend fun ServerSSESession.streamFirehose(
    bus: ChangeBus,
    bookAccessPolicy: () -> BookAccessPolicy,
    sessionLiveness: SessionLiveness?,
    heartbeatIntervalMillis: Long,
    livenessPollMillis: Long,
) {
    // The route group is mounted inside authenticate(JWT_PROVIDER), so a principal
    // is always present in production. The null guard is defence-in-depth — without
    // a user, per-user events cannot be safely filtered, so the stream is refused.
    val principal = call.userPrincipalOrNull() ?: return
    val userId = principal.userId.value
    val role = principal.role

    // Malformed cursor (non-numeric) is treated identically to a stale cursor:
    // a corrupted Room cell or client-side bug must force REST catch-up rather
    // than silently subscribe to the live tail and diverge from server state.
    val lastEventId: Long? =
        call.request.headers["Last-Event-ID"]?.let { raw ->
            raw.toLongOrNull() ?: run {
                log.debug { "sync stream cursor malformed for userId=$userId; sending CursorStale" }
                sendCursorStale(bus.oldestRetainedRevision() ?: 0L)
                return
            }
        }
    // Fast-path pre-check: reject an already-stale cursor before spinning up the
    // heartbeat/control side-jobs. [collectFirehoseEvents] re-runs the same check at actual
    // subscription attach, closing the race window between this snapshot and that attach.
    if (sendCursorStaleIfBehind(bus, lastEventId, userId)) return

    log.info { "sync stream opened: userId=$userId" }

    // Comment-line keepalive every [heartbeatIntervalMillis] ms. The frames
    // (`:keepalive\r\n`) are ignored by the EventSource spec on the client
    // but keep the TCP connection alive across NATs and load balancers,
    // which otherwise drop idle streams. The side-job is cancelled in the
    // `finally` below so it never leaks across requests.
    //
    // Note: Ktor's built-in `heartbeat { }` extension was tried first but
    // its frames don't reach the client through `testApplication`'s SSE
    // transport — likely because `launch(heartbeatJob + ...)` reparents
    // outside the session's structured-concurrency tree. A manual launch
    // on the session's own scope is observable end-to-end.
    val heartbeatJob = launch { runKeepalive(heartbeatIntervalMillis) }

    // Control frames (e.g. AccessChanged) ride a separate, non-replayed bus channel —
    // they carry no revision and must not enter Last-Event-Id resume. Deliver frames
    // addressed to this subscriber, plus content-free BROADCAST frames destined for every
    // subscriber, on the same `event: control` line the CursorStale/StreamError frames
    // use, so the client branches on `event:` alone.
    val controlJob =
        launch {
            bus
                .subscribeControl()
                .filter { it.userId == userId || it.userId == ChangeBus.BROADCAST }
                .collect { frame -> sendControl(frame.control) }
        }

    try {
        coroutineScope {
            val streamJob = launch { collectFirehoseEvents(bus, bookAccessPolicy, userId, role, lastEventId) }
            // C2: re-check the caller's session on a cadence and sever the LIVE firehose the moment
            // it's revoked. The JWT wall only checks at UPGRADE, so without this a revoked device keeps
            // receiving the sync tail until the socket drops. Inert when sessionLiveness is null.
            val livenessJob =
                sessionLiveness?.let { liveness ->
                    launch {
                        while (true) {
                            delay(livenessPollMillis)
                            if (!liveness.isLive(principal.sessionId)) {
                                log.info { "sync stream severed: session revoked userId=$userId" }
                                sendSessionRevoked()
                                streamJob.cancel()
                                return@launch
                            }
                        }
                    }
                }
            streamJob.join()
            livenessJob?.cancel()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isClientDisconnect(e)) {
            // Normal: the client closed the SSE connection (navigated away, backgrounded,
            // or restarted) mid-stream. The write channel is gone, so there's nothing to
            // report and nothing we could send. End the stream quietly — never a 500.
            log.debug { "SSE firehose client disconnected; ending stream" }
        } else {
            sendStreamError(e)
        }
    } finally {
        heartbeatJob.cancel()
        controlJob.cancel()
        log.info { "sync stream closed: userId=$userId" }
    }
}

/**
 * Subscribes to the change bus and streams the [busEvent]s `(userId, role)` is entitled to see —
 * the firehose's core delivery loop. Extracted from [streamFirehose] so the C2 liveness gate and the
 * keepalive/control side-jobs don't inflate that function's cognitive complexity. Skips events at or
 * below [lastEventId] (already-delivered replay), events for other users, and access-gated content.
 *
 * [ChangeBus] is a hot `MutableSharedFlow` (`replay = 256`, `DROP_OLDEST`): a subscriber sees the
 * replay cache starting from wherever the floor sits at the moment its collector actually attaches
 * — not at [streamFirehose]'s pre-subscribe staleness snapshot, taken before this `launch` even
 * starts. Writes landing in that gap can evict past the client's [lastEventId] and the client would
 * see a silent gap instead of `CursorStale`. `onSubscription` fires exactly when the [ChangeBus]
 * subscription attaches, so re-running [sendCursorStaleIfBehind] there closes the window: it throws
 * a plain [CancellationException] on a stale floor, which — thrown from a `launch`ed job's own
 * coroutine rather than delivered via an external `cancel()` — completes that job as cancelled
 * without propagating a failure to [streamFirehose]'s `coroutineScope`, exactly like the C2
 * liveness-revoked path's `streamJob.cancel()`.
 */
internal suspend fun ServerSSESession.collectFirehoseEvents(
    bus: ChangeBus,
    bookAccessPolicy: () -> BookAccessPolicy,
    userId: String,
    role: UserRole,
    lastEventId: Long?,
) {
    bus
        .subscribe()
        .onSubscription {
            if (sendCursorStaleIfBehind(bus, lastEventId, userId)) {
                throw CancellationException("SSE cursor stale at ChangeBus subscription attach")
            }
        }
        // Skip events the client already received. With replay=256, a reconnecting
        // client would otherwise see events from the replay cache that it already
        // processed in a previous session.
        .filter { it.event.revision > (lastEventId ?: 0L) }
        .collect { busEvent ->
            // Per-user scoping: a BusEvent carrying a userId belongs to a
            // user-scoped domain — deliver it only to that user. A null userId
            // is a global-domain event, delivered to every subscriber.
            if (busEvent.userId != null && busEvent.userId != userId) return@collect
            // Access gating: a live content event the subscriber may not see is dropped
            // before send. ROOT/ADMIN and tombstones bypass — see [isBookEventHidden] /
            // [isCollectionEventHidden].
            val gatedReason =
                when {
                    isBookEventHidden(busEvent, userId, role, bookAccessPolicy) -> "book"
                    isActivityEventHidden(busEvent, userId, role, bookAccessPolicy) -> "activity"
                    isCollectionEventHidden(busEvent, userId, role, bookAccessPolicy) -> "collection"
                    isLibraryFolderEventHidden(busEvent, role) -> "libraryFolder"
                    isAdminRosterEventHidden(busEvent, role) -> "adminRoster"
                    else -> null
                }
            if (gatedReason != null) {
                log.trace {
                    "sse gated: domain=${busEvent.repo.domainName} " +
                        "event=${busEvent.event::class.simpleName} userId=$userId reason=$gatedReason"
                }
                return@collect
            }
            // Type-bound: repo and event match by construction, so the repo's
            // serializer is guaranteed to fit the event's payload type.
            log.trace {
                "sse emit: domain=${busEvent.repo.domainName} " +
                    "event=${busEvent.event::class.simpleName} revision=${busEvent.event.revision}"
            }
            send(
                id = busEvent.event.revision.toString(),
                event = busEvent.repo.domainName,
                data = busEvent.repo.encodeSyncEventAsJson(busEvent.event),
            )
        }
}

/**
 * Comment-line keepalive loop for one SSE connection: emits `:keepalive` every
 * [intervalMillis] ms until cancelled. A failed write means the client is gone — end
 * quietly (the main collect's teardown handles the rest); only unexpected errors are
 * logged. Extracted from `streamFirehose` so its try/catch nesting doesn't inflate that
 * function's cognitive complexity.
 */
private suspend fun ServerSSESession.runKeepalive(intervalMillis: Long) {
    try {
        while (coroutineContext.isActive) {
            delay(intervalMillis)
            send(ServerSentEvent(comments = "keepalive"))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (!isClientDisconnect(e)) {
            log.warn(e) { "SSE heartbeat stopped on unexpected error" }
        }
    }
}

/**
 * Best-effort [SyncControl.StreamError] frame for a genuine (non-disconnect) firehose
 * failure. Logs with a correlation id, then attempts the send — if the connection is
 * already gone the write also fails, so it's wrapped in [runCatching] to ensure a dead
 * client never escalates into an unhandled 500.
 */
private suspend fun ServerSSESession.sendStreamError(e: Exception) {
    val cid = Uuid.random().toString()
    log.error(e) { "Uncaught SSE flow exception [cid=$cid]" }
    try {
        send(
            data =
                contractJson.encodeToString(
                    SyncControl.serializer(),
                    SyncControl.StreamError(
                        error =
                            InternalError(
                                correlationId = cid,
                                cause = e::class.simpleName,
                                debugInfo = null,
                            ),
                    ),
                ),
            event = SSE_EVENT_CONTROL,
        )
    } catch (sendError: CancellationException) {
        throw sendError
    } catch (sendError: Exception) {
        log.debug(sendError) { "failed to deliver SSE stream-error event [cid=$cid]" }
    }
}

/**
 * Whether a live firehose [busEvent] must be withheld from `(userId, role)` by the
 * book-level access boundary.
 *
 * Only the `books` domain is gated, and only its *content* events (Created/Updated)
 * which carry a payload a member must not see for a private book. ROOT/ADMIN see every
 * book, so they skip the [BookAccessPolicy.canAccess] probe entirely — no DB hit.
 *
 * Deleted tombstones are never hidden: `canAccess` requires `deleted_at IS NULL`, so a
 * deleted book is never "accessible" and probing it would drop every tombstone for every
 * viewer — stranding stale Room rows that can never be reconciled. A tombstone carries
 * only an id (no content), so delivering it to a subscriber who never had the book simply
 * no-ops on their side. One DB probe per gated event per member — fine at our scale.
 */
private suspend fun isBookEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    if (busEvent.repo.domainName != BOOKS_DOMAIN) return false
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    return !bookAccessPolicy().canAccess(userId, role, busEvent.event.id)
}

/**
 * Whether a live firehose [busEvent] on the `activities` domain must be withheld from
 * `(userId, role)`. Book-gated: a row with a non-null `book_id` is hidden unless the caller can
 * access that book; a `book_id == null` row (e.g. `user_joined`) is public and always passes.
 *
 * Mirrors [isBookEventHidden]: ROOT/ADMIN and Deleted tombstones always pass (a tombstone strands
 * no secret). Visibility matches the `activities` catch-up fragment exactly (`book_id IS NULL OR
 * book_id IN accessible`), so the live tail and REST replay never disagree.
 */
private suspend fun isActivityEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    if (busEvent.repo.domainName != ACTIVITIES_DOMAIN) return false
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false
    // Gate on the row's book_id (from the payload), not the event id (which is the activity id).
    val bookId = activityBookIdOf(busEvent.event) ?: return false
    return !bookAccessPolicy().canAccess(userId, role, bookId)
}

/**
 * The `bookId` carried by a content [event] on the `activities` domain, or `null` when the row is
 * a public (non-book) activity or a tombstone (already handled upstream). The repo↔event type
 * binding guarantees the payload is an [ActivitySyncPayload] by construction.
 */
private fun activityBookIdOf(event: SyncEvent<*>): String? =
    when (event) {
        is SyncEvent.Created<*> -> (event.payload as ActivitySyncPayload).bookId
        is SyncEvent.Updated<*> -> (event.payload as ActivitySyncPayload).bookId
        is SyncEvent.Deleted -> null
    }

/**
 * Whether a live firehose [busEvent] on the `library_folders` domain must be withheld from
 * [role]. The domain is admin-only — its rows carry absolute server filesystem paths — so a
 * non-admin sees nothing on it.
 *
 * Unlike [isBookEventHidden] / [isCollectionEventHidden], tombstones are withheld too: this is
 * a whole-domain gate, not a per-row one, so a member holds no folder rows and has nothing to
 * reconcile. Matches the [LIBRARY_FOLDERS_HIDDEN] catch-up fragment exactly, so the live tail
 * and REST replay never disagree.
 */
private fun isLibraryFolderEventHidden(
    busEvent: BusEvent<*>,
    role: UserRole,
): Boolean = busEvent.repo.domainName == LIBRARY_FOLDERS_DOMAIN && !isAdmin(role)

/**
 * Whether a live firehose [busEvent] on the `admin_user_roster` domain must be withheld from
 * [role]. The domain is admin-only — its rows carry a user's email/role/status — so a
 * non-admin sees nothing on it.
 *
 * Whole-domain gate, same as [isLibraryFolderEventHidden]: tombstones are withheld too, since a
 * member holds no roster rows and has nothing to reconcile. Matches the
 * [ADMIN_USER_ROSTER_HIDDEN] catch-up fragment exactly, so the live tail and REST replay never
 * disagree.
 */
private fun isAdminRosterEventHidden(
    busEvent: BusEvent<*>,
    role: UserRole,
): Boolean = busEvent.repo.domainName == ADMIN_USER_ROSTER_DOMAIN && !isAdmin(role)

/**
 * Whether a live firehose [busEvent] on a collection domain
 * (`collections` / `collection_shares` / `collection_books`) must be withheld from
 * `(userId, role)` by the collection-level access boundary.
 *
 * Mirrors [isBookEventHidden]: only content events (Created/Updated) are gated; ROOT/ADMIN
 * and Deleted tombstones always pass (a tombstone strands no secret — it only lets a client
 * reconcile a row it may already hold; gating it would permanently leave stale rows).
 *
 * Visibility matches each domain's catch-up fragment exactly so the live tail and REST
 * replay never disagree:
 *  - `collections` — the event id *is* the collection id; gated by [BookAccessPolicy.canAccessCollection].
 *  - `collection_books` — the event id is an opaque per-row value (SERVER-SYNC-04: it encodes
 *    nothing), so the collection id comes from the [CollectionBookSyncPayload] carried by the
 *    Created/Updated event, never parsed off the id; gated by that collection's access.
 *  - `collection_shares` — the event id is the grant row id, so the collection id and named
 *    user come from the [CollectionShareSyncPayload]; visible iff the grant names the viewer
 *    or the viewer owns the collection (the `visibleCollectionGrantIdsSql` rule).
 */
private suspend fun isCollectionEventHidden(
    busEvent: BusEvent<*>,
    userId: String,
    role: UserRole,
    bookAccessPolicy: () -> BookAccessPolicy,
): Boolean {
    val domain = busEvent.repo.domainName
    if (domain != COLLECTIONS_DOMAIN && domain != COLLECTION_SHARES_DOMAIN && domain != COLLECTION_BOOKS_DOMAIN) {
        return false
    }
    if (role == UserRole.ROOT || role == UserRole.ADMIN) return false
    if (busEvent.event is SyncEvent.Deleted) return false

    if (domain == COLLECTION_SHARES_DOMAIN) {
        val share = sharePayloadOf(busEvent.event) ?: return false
        if (share.sharedWithUserId == userId) return false
        return !bookAccessPolicy().ownsCollection(userId, share.collectionId)
    }

    val collectionId =
        if (domain == COLLECTION_BOOKS_DOMAIN) {
            collectionBookPayloadOf(busEvent.event)?.collectionId
        } else {
            busEvent.event.id
        }
    // A missing collection_books payload should never happen for Created/Updated (only Deleted
    // carries none, and that already returned above) — hide defensively rather than bypass.
    return collectionId == null || !bookAccessPolicy().canAccessCollection(userId, role, collectionId)
}

/**
 * Extracts the [CollectionBookSyncPayload] carried by a Created/Updated `collection_books`
 * event, or null for a Deleted event (which carries no payload — callers never reach this for
 * Deleted, since [isCollectionEventHidden] returns early on tombstones). Mirrors [sharePayloadOf].
 */
private fun collectionBookPayloadOf(event: SyncEvent<*>): CollectionBookSyncPayload? =
    when (event) {
        is SyncEvent.Created<*> -> event.payload as CollectionBookSyncPayload
        is SyncEvent.Updated<*> -> event.payload as CollectionBookSyncPayload
        is SyncEvent.Deleted -> null
    }

/**
 * The [CollectionShareSyncPayload] carried by a content [event] on the `collection_shares`
 * domain, or `null` if the event carries no payload (a tombstone — already handled upstream).
 * The repo↔event type binding guarantees the payload is a share payload by construction.
 */
private fun sharePayloadOf(event: SyncEvent<*>): CollectionShareSyncPayload? =
    when (event) {
        is SyncEvent.Created<*> -> event.payload as CollectionShareSyncPayload
        is SyncEvent.Updated<*> -> event.payload as CollectionShareSyncPayload
        is SyncEvent.Deleted -> null
    }

/**
 * Best-effort terminal frame telling the client its session was revoked/expired mid-stream (C2). The
 * client treats [SyncControl.StreamError] as "reconnect and try again"; the reconnect's UPGRADE hits
 * the JWT auth wall, which now finds the session dead and returns 401 — so the device is severed and
 * the client surfaces a genuine re-auth. Wrapped in try/catch because a revoked client may already be
 * gone. [AuthError.SessionExpired] covers the revoked case too (expiry or revocation).
 */
private suspend fun ServerSSESession.sendSessionRevoked() {
    try {
        send(
            data =
                contractJson.encodeToString(
                    SyncControl.serializer(),
                    SyncControl.StreamError(error = AuthError.SessionExpired()),
                ),
            event = SSE_EVENT_CONTROL,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.debug(e) { "failed to deliver SSE session-revoked frame" }
    }
}

/** Emits a [SyncControl.CursorStale] control frame on the firehose. */
private suspend fun ServerSSESession.sendCursorStale(lastKnownRevision: Long) {
    sendControl(SyncControl.CursorStale(lastKnownRevision = lastKnownRevision))
}

/**
 * Pure predicate: is [lastEventId] behind [bus]'s CURRENT replay-buffer floor? Returns the floor
 * revision (the value a [SyncControl.CursorStale] frame should carry) when stale, `null` when the
 * cursor is fresh. "clientCursor < oldestRetained" → stale; a `null` [lastEventId] or a `null`
 * [ChangeBus.oldestRetainedRevision] (empty buffer) is never stale.
 *
 * Extracted from [sendCursorStaleIfBehind] so the staleness check itself — the exact race the
 * attach-time re-check in [collectFirehoseEvents] closes — is unit-testable without an SSE
 * session: [ChangeBus] is a hot `MutableSharedFlow` (`replay = 256`, `DROP_OLDEST`), so a
 * subscriber sees the replay cache starting from wherever the floor sits at actual subscription
 * attach, not at [streamFirehose]'s pre-subscribe snapshot taken before that subscription even
 * exists. A burst landing in that gap can evict past [lastEventId] with no live signal.
 */
internal fun staleCursorFloor(
    bus: ChangeBus,
    lastEventId: Long?,
): Long? {
    val oldestRetained = bus.oldestRetainedRevision()
    return if (lastEventId != null && oldestRetained != null && lastEventId < oldestRetained) oldestRetained else null
}

/**
 * Checks [lastEventId] against [bus] via [staleCursorFloor] and, if stale, sends
 * [SyncControl.CursorStale] and returns `true`. Shared by two call sites that must agree on the
 * same check: [streamFirehose]'s pre-subscribe fast path (rejects an already-stale cursor before
 * the heartbeat/control side-jobs spin up) and [collectFirehoseEvents]'s attach-time re-check.
 */
private suspend fun ServerSSESession.sendCursorStaleIfBehind(
    bus: ChangeBus,
    lastEventId: Long?,
    userId: String,
): Boolean {
    val floor = staleCursorFloor(bus, lastEventId) ?: return false
    log.debug {
        "sync stream cursor stale: userId=$userId lastEventId=$lastEventId " +
            "oldestRetained=$floor; sending CursorStale"
    }
    sendCursorStale(floor)
    return true
}

/** Emits an arbitrary [SyncControl] frame on the firehose's `event: control` line. */
private suspend fun ServerSSESession.sendControl(control: SyncControl) {
    send(
        data = contractJson.encodeToString(SyncControl.serializer(), control),
        event = SSE_EVENT_CONTROL,
    )
}
