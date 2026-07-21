package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.ktor.ext.inject

private val log = KotlinLogging.logger("com.calypsan.listenup.server.sync.SyncRoutes")

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

// Splices into `id IN (...)` to yield no rows — hides the library_folders domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
private val LIBRARY_FOLDERS_HIDDEN =
    SqlFragment(sql = "SELECT id FROM library_folders WHERE 1 = 0", args = emptyList())

// Splices into `id IN (...)` to yield no rows — hides the admin_user_roster domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
private val ADMIN_USER_ROSTER_HIDDEN =
    SqlFragment(sql = "SELECT id FROM admin_user_roster WHERE 1 = 0", args = emptyList())

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
 *  - `GET /api/v1/sync/<domain>?since=<rev>&limit=<n>` — paginated catch-up
 *  - `GET /api/v1/sync/<domain>/digest?cursor=<rev>` — drift detection
 *  - `GET /api/v1/sync/domains` — registered domain list
 *
 * The live firehose is NOT here: it streams over the RPC socket via
 * [SyncStreamServiceImpl] (`SyncStreamService.observeEvents`), gated by the same
 * [firehoseGateReason] chain these routes' access filters mirror.
 */
fun Route.syncRoutes() {
    val registry by inject<SyncRegistry>()
    val bookAccessPolicy by inject<BookAccessPolicy>()

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
