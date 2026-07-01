package com.calypsan.listenup.server

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.seed.SeedRunner
import com.calypsan.listenup.server.services.AdminUserRosterMaintainer
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import io.ktor.server.application.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.inject

/**
 * One-time startup backfill for the `public_profiles` projection.
 *
 * Pre-existing users (created before the V31 migration that added the table) have
 * no projection row until something refreshes them. This call populates them once,
 * guarded by an emptiness check so subsequent boots skip it instantly. Must run
 * after schema migrations (so `public_profiles` exists) and after Koin starts (so
 * [PublicProfileMaintainer] is resolvable) — both are guaranteed by calling this
 * from [module] after [installDependencies].
 *
 * Runs synchronously on the module init thread via [runBlocking], matching the idiom
 * used by the genre-taxonomy seeder in [launchSeeders].
 */
internal fun Application.backfillPublicProfiles() {
    val sql by inject<ListenUpDatabase>()
    val maintainer by inject<PublicProfileMaintainer>()
    runBlocking {
        runCatching {
            val isEmpty =
                suspendTransaction(sql) {
                    sql.publicProfilesQueries.isEmpty().executeAsOne()
                }
            if (isEmpty) maintainer.backfillAll()
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.error(e) { "public_profiles startup backfill failed — projection will self-heal on next refresh" }
        }
    }
}

/**
 * One-time startup backfill for the `admin_user_roster` projection.
 *
 * Pre-existing users (created before the projection existed) have no roster row until
 * something refreshes them. This call populates them once, guarded by an emptiness check
 * so subsequent boots skip it instantly. Must run after schema migrations (so
 * `admin_user_roster` exists) and after Koin starts (so [AdminUserRosterMaintainer] is
 * resolvable) — both are guaranteed by calling this from [module] after [installDependencies].
 * Mirrors [backfillPublicProfiles].
 */
internal fun Application.backfillAdminUserRoster() {
    val sql by inject<ListenUpDatabase>()
    val maintainer by inject<AdminUserRosterMaintainer>()
    runBlocking {
        runCatching {
            val isEmpty =
                suspendTransaction(sql) {
                    sql.adminUserRosterQueries.isEmpty().executeAsOne()
                }
            if (isEmpty) maintainer.backfillAll()
        }.onFailure { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.error(e) { "admin_user_roster startup backfill failed — projection will self-heal on next refresh" }
        }
    }
}

/**
 * Kicks off seed jobs after Koin is installed. In demo profile we run the full
 * [SeedRunner] (curated demo users + library + tags + genres + …). In any other
 * profile, we still seed the default Genre taxonomy on fresh installs because
 * the genre tree is the curator's starting point, not demo content — the
 * seeder's `isAlreadySeeded` guard keeps subsequent runs no-ops.
 */
internal fun Application.launchSeeders(
    scope: CoroutineScope,
    seedProfile: String?,
    libraryConfigured: Boolean,
) {
    if (seedProfile == SEED_PROFILE_DEMO) {
        val seedRunner by inject<SeedRunner>()
        scope.launch {
            runCatching { seedRunner.run() }
                .onFailure { it.logUnlessCancelled("demo seeding failed — server keeps running") }
        }
    } else if (libraryConfigured) {
        val genreSeeder by inject<com.calypsan.listenup.server.seed.GenreDomainSeeder>()
        val moodSeeder by inject<com.calypsan.listenup.server.seed.MoodDomainSeeder>()
        val pendingGenrePromotion by inject<com.calypsan.listenup.server.services.PendingGenrePromotion>()
        // Synchronous on the module init thread — `module()` returns only after the
        // default taxonomy is in place. Pays the cost (~50-100ms of SQLite writes) once
        // on first install; subsequent boots are a single `count()` query via
        // `isAlreadySeeded`. The async-launch alternative leaked seed coroutines past
        // test boundaries on CI, racing scanner-test bootstrap scans.
        kotlinx.coroutines.runBlocking {
            runCatching {
                if (!genreSeeder.isAlreadySeeded()) genreSeeder.seed()
            }.onFailure { it.logUnlessCancelled("genre default-taxonomy seeding failed — server keeps running") }
            // Seed the canonical Audible mood vocabulary on fresh installs (curator
            // dedupe anchors, not demo content). Idempotent via `isAlreadySeeded`.
            runCatching {
                if (!moodSeeder.isAlreadySeeded()) moodSeeder.seed()
            }.onFailure { it.logUnlessCancelled("mood vocabulary seeding failed — server keeps running") }
            // One-time: drain the legacy pending-genre backlog into live genres so an
            // existing library lights up. Runs after seeding (resolution prefers the
            // seeded taxonomy before auto-creating). Idempotent — a drained queue makes
            // subsequent boots a single empty-queue query.
            runCatching { pendingGenrePromotion.run() }
                .onFailure { it.logUnlessCancelled("pending-genre backlog promotion failed — server keeps running") }
        }
    }
}

/**
 * Re-raises [CancellationException] (so structured concurrency stays intact) and
 * logs every other throwable at error level under [message]. The shared tail for
 * the boot-time seed/promotion jobs, which must never bring the server down.
 */
private fun Throwable.logUnlessCancelled(message: String) {
    if (this is kotlinx.coroutines.CancellationException) throw this
    logger.error(this) { message }
}
