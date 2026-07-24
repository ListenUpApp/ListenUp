package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Pin: any concrete `SqlSyncableRepository` subclass whose **root table carries a `user_id`
 * column** MUST be filtered — either per-user (`override val userScoped = true`) or per-row by
 * an access policy (`override val driver`, the access-filtered catch-up/digest path).
 *
 * A `user_id` root column means the rows belong to individual users. With neither marker the
 * base defaults `userScoped = false` and takes the global substrate path, so `pullSince`/`digest`
 * read and ship **every user's rows to every user** — the data-isolation bug this rule exists to
 * prevent.
 *
 * **Why this is keyed on the schema, not on a Kotlin supertype.**
 * The predecessor of this rule matched a repository's paired `<Domain>Table` object against the
 * Exposed base `UserScopedSyncableTable`. The sync substrate then migrated to SQLDelight: the
 * base became `SqlSyncableRepository` and the table objects ceased to exist. The rule kept
 * compiling, matched zero declarations, and passed green for every release since — while five
 * per-user repositories shipped relying solely on hand-written `userScoped` overrides. Its
 * "current state" KDoc still claimed "no production per-user repository exists yet".
 *
 * A Kotlin-side marker cannot carry this check: the only independent evidence that a domain is
 * per-user is the `user_id` column itself, so the rule reads the SQLDelight schema and treats it
 * as the source of truth. That makes it rename-proof in the direction that matters — a *Kotlin*
 * refactor cannot hollow it out — and the vacuity guards below fail the build if repository or
 * schema discovery ever stops matching.
 *
 * **The two legitimate filtering strategies** (verified exhaustive and disjoint when written —
 * 5 user-scoped, 7 access-filtered, 9 global with no `user_id`):
 *  - `userScoped = true` → the `*ForUser` substrate variants append `AND user_id = ?`
 *    (shelves, shelf_books, playback_positions, listening_events, user_stats).
 *  - `override val driver` → the base splices a runtime access subquery instead; the row's
 *    `user_id` is an actor or grantee rather than an owner, so visibility is by book or by grant
 *    (activities, collection_grants, …). These are exactly the domains registered in
 *    `SyncRoutes.ACCESS_FILTERS`.
 */
class UserScopedRepositoryScopesByUserRule :
    FunSpec({

        test("every syncable repository whose root table carries user_id is user- or access-filtered") {
            val repositories = syncableRepositories()

            // Vacuity guards. The predecessor rule died silently because a base-class rename left
            // its filter matching nothing, and it had no such assertion. If either trips, the rule
            // has stopped testing anything — fix the discovery, don't delete the check.
            repositories.shouldNotBeEmpty()
            repositories
                .filter {
                    sqlColumnsOf(
                        it.rootTable.orEmpty(),
                    )?.contains(USER_ID_COLUMN) == true
                }.shouldNotBeEmpty()

            val offenders =
                repositories.mapNotNull { repo ->
                    val columns = repo.rootTable?.let { sqlColumnsOf(it) } ?: return@mapNotNull null
                    if (USER_ID_COLUMN !in columns) return@mapNotNull null
                    if (repo.isUserScoped || repo.isAccessFiltered) return@mapNotNull null
                    "${repo.name}: root table `${repo.rootTable}` has a $USER_ID_COLUMN column but the " +
                        "repository neither overrides `userScoped = true` (per-user filtering) nor " +
                        "`driver` (access-filtered path) — its rows would be read globally, " +
                        "leaking every user's data to every other user"
                }

            offenders.shouldBeEmpty()
        }

        test("every syncable repository resolves a root table that exists in the schema") {
            // Separate assertion so an unresolvable repository fails loudly rather than being
            // skipped by the rule above — a silent skip is how the predecessor rotted. This also
            // pins the queries-name → `.sq` file → first CREATE TABLE resolution: the tempting
            // snake_case shortcut mis-resolves `seriesQueries` to a `series` table that does not
            // exist (the real one is `book_series`), which would skip that repository unnoticed.
            val unresolved =
                syncableRepositories()
                    .filter { it.rootTable == null || sqlColumnsOf(it.rootTable) == null }
                    .map { "${it.name} (queries=${it.queriesName}, rootTable=${it.rootTable})" }
            unresolved.shouldBeEmpty()
        }
    })
