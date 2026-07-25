package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.api.sync.TargetedMatch

/*
 * Shared read-side support for sync pulls: the per-domain access filters, the page-size bounds,
 * and the targeted-fetch caps and column allowlists.
 *
 * These lived in `SyncRoutes.kt` while catch-up was a REST surface. That surface is retired —
 * the pull now rides `SyncStreamService` over the same RPC socket as the firehose — so the
 * rules moved here rather than dying with the routes. Access filtering is the security-relevant
 * half: it applies per-domain at read time, so a member never receives rows for content outside
 * their grants, on either transport.
 */

// Cap on a single targeted fetch ([TargetedMatch], the scoped AccessChanged delta). The client
// chunks larger scopes into ≤ this many ids per request; the server rejects an over-cap request
// rather than silently truncating — a truncated response would look to the client like "these ids
// are no longer accessible" and wrongly tombstone them.
internal const val MAX_TARGETED_IDS = 100

// Domains whose rows carry a `book_id` column, so [TargetedMatch.BOOK_ID] (the activities half of
// the scoped AccessChanged delta) can match on it. The match column is code-controlled, never user
// input — and matching BOOK_ID against a domain WITHOUT a `book_id` column would be a SQL error —
// so a request naming any other domain fails with SyncError.UnsupportedMatch rather than querying
// a phantom column.
internal val BOOK_ID_MATCH_DOMAINS = setOf(ACTIVITIES_DOMAIN)

// Domains whose rows carry a `collection_id` column, so [TargetedMatch.COLLECTION_ID] (the
// collection-membership half of the scoped AccessChanged delta) can match on it — the sibling
// allowlist to BOOK_ID_MATCH_DOMAINS above. `collection_grants` (wire "collection_shares") also has
// a `collection_id` column and a wired driver, so it would not SQL-error, but no client caller
// targets it today, so the allowlist stays scoped to `collection_books` rather than growing to
// match every column that happens to exist.
internal val COLLECTION_ID_MATCH_DOMAINS = setOf(COLLECTION_BOOKS_DOMAIN)

// Splices into `id IN (...)` to yield no rows — hides the library_folders domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
internal val LIBRARY_FOLDERS_HIDDEN =
    SqlFragment(sql = "SELECT id FROM library_folders WHERE 1 = 0", args = emptyList())

// Splices into `id IN (...)` to yield no rows — hides the admin_user_roster domain from
// non-admins on catch-up/digest. `1 = 0` is a constant predicate, no interpolated input.
internal val ADMIN_USER_ROSTER_HIDDEN =
    SqlFragment(sql = "SELECT id FROM admin_user_roster WHERE 1 = 0", args = emptyList())

/**
 * How a wire domain's sync catch-up/digest is access-filtered — declared **data**, not control
 * flow. [ACCESS_FILTERS] maps each gated domain to its spec and [accessFilterFor] is a lookup, so
 * the per-row-vs-role-gated classification is a value a test can read directly (via
 * [perRowAccessGatedSyncDomains] / [roleGatedSyncDomains]) rather than parse out of a `when`.
 */
internal sealed interface AccessFilterSpec {
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
internal val ACCESS_FILTERS: Map<String, AccessFilterSpec> =
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
internal fun accessFilterFor(
    domainName: String,
    userId: String,
    role: UserRole,
    policy: () -> BookAccessPolicy,
): SqlFragment? = ACCESS_FILTERS[domainName]?.fragment(userId, role, policy)

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

/** Page-size bounds for a catch-up pull; the client's requested limit is clamped into this range. */
internal const val MIN_PAGE_LIMIT = 1
internal const val MAX_PAGE_LIMIT = 5000

/**
 * The storage column a [TargetedMatch] resolves to.
 *
 * The mapping lives server-side on purpose: the wire carries the contract enum, never a column
 * name, so storage identifiers stay out of the permanent client-facing API.
 */
internal val TargetedMatch.column: String
    get() =
        when (this) {
            TargetedMatch.ID -> "id"
            TargetedMatch.COLLECTION_ID -> "collection_id"
            TargetedMatch.BOOK_ID -> "book_id"
        }

/**
 * Whether [domain] rows actually carry this match's column.
 *
 * Honouring a match against a column a domain does not have would be a SQL error, so the
 * allowlists are the guard — and they stay deliberately narrow: a domain is listed only when a
 * real caller targets it, not merely because the column happens to exist.
 */
internal fun TargetedMatch.isSupportedFor(domain: String): Boolean =
    when (this) {
        TargetedMatch.ID -> true
        TargetedMatch.COLLECTION_ID -> domain in COLLECTION_ID_MATCH_DOMAINS
        TargetedMatch.BOOK_ID -> domain in BOOK_ID_MATCH_DOMAINS
    }
