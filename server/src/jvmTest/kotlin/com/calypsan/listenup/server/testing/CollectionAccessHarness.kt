package com.calypsan.listenup.server.testing

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.CollectionAccessPolicy
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.BookRepository
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.GenreRepository
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import kotlin.time.Instant

/**
 * The shared fixture behind the collection access-model guard suite (G0–G5). Wires a real
 * [CollectionServiceImpl] over a Flyway-migrated in-memory SQLite db, alongside the single
 * [BookAccessPolicy] visibility seam and a [BookRepository] armed with the removal-cascade repos
 * — so every guard drives the same production seams a client would, and asserts through
 * `canAccess` / `pullSince` (the invariant) rather than junction-table reads (the mechanism).
 *
 * Extracted from `CollectionAccessModelExclusivityTest` so the exclusivity tests, the
 * `AccessInvariantMatrixTest`, and the emission-contract test share one wiring — a drift in how
 * the service is constructed can't leave one guard testing a different graph than another.
 *
 * @property bookRepo a [BookRepository] wired with the `book_tags` / `book_moods` /
 *   `collection_books` cascade repos, so a guard can drive real book removal
 *   ([BookRepository.softDelete]) and re-add ([BookRepository.reviveByIds]) and observe the
 *   access consequence. Uses the default (stepping) `Clock.System` so a revive's
 *   `deletedAt >= floor` window is honoured.
 * @property revisionTouch the [FakeBookRevisionTouch] the service bumps — exposed so a guard can
 *   assert which books a membership change touched, without a real book repo in the service graph.
 */
internal data class CollectionAccessHarness(
    val service: CollectionServiceImpl,
    val collectionRepo: CollectionRepository,
    val collectionBookRepo: CollectionBookRepository,
    val grantRepo: CollectionGrantRepository,
    val bookAccessPolicy: BookAccessPolicy,
    val bookRepo: BookRepository,
    val bus: ChangeBus,
    val db: ListenUpDatabase,
    val driver: SqlDriver,
    val revisionTouch: FakeBookRevisionTouch,
)

/** The fixed clock the collection service runs on — deterministic membership timestamps. */
val harnessFixedClock: FixedClock = FixedClock(Instant.fromEpochMilliseconds(1_700_000_000_000L))

/** A [PrincipalProvider] yielding [userId] with [role] — the per-caller binding the service copies in. */
fun principalFor(
    userId: String,
    role: UserRole = UserRole.MEMBER,
): PrincipalProvider =
    PrincipalProvider {
        UserPrincipal(UserId(userId), SessionId("session-$userId"), role)
    }

/**
 * Builds the shared [CollectionAccessHarness] over this migrated test db. The [bus] and
 * [SyncRegistry] are shared across every repo (matching production), so the collection service and
 * the book repo publish onto the same change bus.
 */
internal fun SqlTestDatabases.collectionAccessHarness(): CollectionAccessHarness {
    val bus = ChangeBus()
    val registry = SyncRegistry()
    val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = registry, driver = driver)
    val accessPolicy = CollectionAccessPolicy(collectionRepo, grantRepo)
    val revisionTouch = FakeBookRevisionTouch()
    val service =
        CollectionServiceImpl(
            collectionRepo = collectionRepo,
            collectionBookRepo = collectionBookRepo,
            grantRepo = grantRepo,
            accessPolicy = accessPolicy,
            bus = bus,
            sql = sql,
            clock = harnessFixedClock,
            permissionPolicy = UserPermissionPolicy(sql),
            bookRevisionTouch = revisionTouch,
            principal = principalFor("admin", UserRole.ADMIN),
        )
    val bookRepo =
        BookRepository(
            db = sql,
            driver = driver,
            bus = bus,
            registry = registry,
            contributorRepository = ContributorRepository(sql, bus, registry),
            seriesRepository = SeriesRepository(sql, bus, registry),
            genreRepository = GenreRepository(sql, bus, registry),
            collectionBookRepository = collectionBookRepo,
            bookTagRepository = BookTagRepository(db = sql, bus = bus, registry = registry),
            bookMoodRepository = BookMoodRepository(db = sql, bus = bus, registry = registry),
        )
    return CollectionAccessHarness(
        service = service,
        collectionRepo = collectionRepo,
        collectionBookRepo = collectionBookRepo,
        grantRepo = grantRepo,
        bookAccessPolicy = BookAccessPolicy(sql, driver),
        bookRepo = bookRepo,
        bus = bus,
        db = sql,
        driver = driver,
        revisionTouch = revisionTouch,
    )
}

/** Returns a copy of the service scoped to [userId] with [role] — how a route binds the caller. */
internal fun CollectionServiceImpl.actAs(
    userId: String,
    role: UserRole = UserRole.MEMBER,
): CollectionServiceImpl = copyWith(principalFor(userId, role))

/** Grants [userId] the default `ALL_BOOKS` read grant every member holds at registration. */
internal suspend fun CollectionAccessHarness.grantAllBooks(
    allBooksId: String,
    userId: String,
) {
    grantRepo.upsert(
        CollectionShareSyncPayload(
            id = "grant-$allBooksId-$userId",
            collectionId = allBooksId,
            sharedWithUserId = userId,
            sharedByUserId = "system",
            permission = SharePermission.Read,
            revision = 0L,
            updatedAt = 0L,
            deletedAt = null,
        ),
    )
}

/**
 * The raw live-collection-id set the book currently belongs to — a **mechanism diagnostic**, never
 * a load-bearing assertion. A guard reads this only to make a failure legible; the invariant is
 * asserted through [BookAccessPolicy.canAccess] / `pullSince`, which is what a user actually sees.
 */
internal suspend fun CollectionAccessHarness.junctionDiagnostic(bookId: String): Set<String> =
    collectionBookRepo.findCollectionIdsForBook(bookId).toSet()
