package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.BookEntity
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.CollectionEntity
import com.calypsan.listenup.client.data.local.db.CollectionShareEntity
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.GenreEntity
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.MoodEntity
import com.calypsan.listenup.client.data.local.db.PlaybackPositionEntity
import com.calypsan.listenup.client.data.local.db.PublicProfileEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.ShelfBookEntity
import com.calypsan.listenup.client.data.local.db.ShelfEntity
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.UserStatsEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity
import com.calypsan.listenup.client.data.sync.domains.syncDomainCatalog
import com.calypsan.listenup.client.data.sync.testing.StubAvatarDownloadRepository
import com.calypsan.listenup.client.domain.repository.LibrarySync
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import dev.mokkery.mock
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * `clearLibraryData` is the sign-out / server-switch reset. It wipes the library
 * rows, but the per-domain sync cursors are part of the same lifecycle: if the
 * rows are gone but the cursors survive, the next login's catch-up resumes from
 * the stale high-water cursor (`?since=<oldCursor>`, server filters
 * `revision > cursor` strictly) and returns nothing for an unchanged library —
 * leaving the freshly-wiped tables permanently empty with no manual recovery.
 *
 * This pins the invariant: **resetting the library also resets its sync cursors**,
 * so catch-up re-pulls the whole library from scratch.
 */
class LibraryResetHelperTest :
    FunSpec({

        test("clearLibraryData also clears the per-domain sync cursors") {
            val db = createInMemoryTestDatabase()
            try {
                // Seed cursors as a synced client would hold them after catch-up.
                db.syncCursorDao().setCursor(SyncCursorEntity(domainName = "books", revision = 4200))
                db.syncCursorDao().setCursor(SyncCursorEntity(domainName = "contributors", revision = 87))

                val helper =
                    LibraryResetHelperImpl(
                        database = db,
                        transactionRunner = RoomTransactionRunner(db),
                        librarySyncContract = mock<LibrarySync>(),
                    )

                helper.clearLibraryData(discardPendingOperations = true)

                // After a reset, no cursor may survive — otherwise catch-up never
                // re-pulls the wiped library on the next login.
                db.syncCursorDao().all().shouldBeEmpty()
            } finally {
                db.close()
            }
        }

        test(
            "clearLibraryData empties every mirrored sync domain's backing table " +
                "(drift-proof against SyncDomainCatalog)",
        ) {
            val db = createInMemoryTestDatabase()
            try {
                val catalog =
                    syncDomainCatalog(
                        database = db,
                        mapper = BookEntityMapper(),
                        imageStorage = stubImageStorage(),
                        authSession = FakeAuthSession(userId = "reset-user"),
                        avatarDownloadRepository = StubAvatarDownloadRepository(),
                        pingPresence = {},
                        refetchServerInfo = {},
                        refetchPreferences = {},
                    )

                // One probe per mirrored domain: seeds a single row into that domain's backing
                // table and reports whether the row is still there. The equality check right below
                // is the drift guard — it derives coverage from the catalog itself, so a domain
                // ADDED to (or renamed in) SyncDomainCatalog without a matching probe here fails
                // THIS assertion, before clearLibraryData's completeness could silently regress
                // again. Seeded via each DAO's own upsert (not raw SQL) so the FK on
                // library_folders -> libraries is honoured; libraries is probed before
                // library_folders for that reason.
                val probes =
                    listOf(
                        DomainProbe(
                            domainName = "tags",
                            seed = {
                                db.tagDao().upsert(TagEntity(id = "seed-tag", name = "n", slug = "n", updatedAt = 0L))
                            },
                            isGone = { db.tagDao().revisionOf("seed-tag") == null },
                        ),
                        DomainProbe(
                            domainName = "genres",
                            seed = {
                                db.genreDao().upsert(GenreEntity(id = "seed-genre", name = "n", slug = "n", path = "/n"))
                            },
                            isGone = { db.genreDao().revisionOf("seed-genre") == null },
                        ),
                        DomainProbe(
                            domainName = "moods",
                            seed = {
                                db.moodDao().upsert(
                                    MoodEntity(id = "seed-mood", name = "n", slug = "n", updatedAt = 0L),
                                )
                            },
                            isGone = { db.moodDao().revisionOf("seed-mood") == null },
                        ),
                        DomainProbe(
                            domainName = "book_tags",
                            seed = {
                                db.bookTagDao().upsert(
                                    BookTagEntity(bookId = "b1", tagId = "t1", syncId = "b1:t1", createdAt = 0L),
                                )
                            },
                            isGone = { db.bookTagDao().findByKey("b1", "t1") == null },
                        ),
                        DomainProbe(
                            domainName = "book_moods",
                            seed = {
                                db.bookMoodDao().upsert(
                                    BookMoodEntity(bookId = "b1", moodId = "m1", syncId = "b1:m1", createdAt = 0L),
                                )
                            },
                            isGone = { db.bookMoodDao().findByKey("b1", "m1") == null },
                        ),
                        DomainProbe(
                            domainName = "libraries",
                            seed = {
                                db.libraryDao().upsert(
                                    LibraryEntity(
                                        id = "seed-lib",
                                        name = "n",
                                        metadataPrecedence = "embedded",
                                        accessMode = "PRIVATE",
                                        createdByUserId = null,
                                        createdAt = 0L,
                                        revision = 0L,
                                        deletedAt = null,
                                        initialScanCompletedAt = null,
                                    ),
                                )
                            },
                            isGone = { db.libraryDao().findById("seed-lib") == null },
                        ),
                        DomainProbe(
                            domainName = "library_folders",
                            seed = {
                                db.libraryFolderDao().upsert(
                                    LibraryFolderEntity(
                                        id = "seed-folder",
                                        libraryId = "seed-lib",
                                        rootPath = "/x",
                                        createdAt = 0L,
                                        revision = 0L,
                                        deletedAt = null,
                                    ),
                                )
                            },
                            isGone = { db.libraryFolderDao().findById("seed-folder") == null },
                        ),
                        DomainProbe(
                            domainName = "shelves",
                            seed = {
                                db.shelfDao().upsert(
                                    ShelfEntity(
                                        id = "seed-shelf",
                                        name = "n",
                                        description = "",
                                        isPrivate = false,
                                        updatedAt = 0L,
                                        createdAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.shelfDao().getById("seed-shelf") == null },
                        ),
                        DomainProbe(
                            domainName = "shelf_books",
                            seed = {
                                db.shelfBookDao().upsert(
                                    ShelfBookEntity(
                                        id = "seed-shelf:b1",
                                        shelfId = "seed-shelf",
                                        bookId = "b1",
                                        sortOrder = 0,
                                        updatedAt = 0L,
                                        createdAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.shelfBookDao().findById("seed-shelf:b1") == null },
                        ),
                        DomainProbe(
                            domainName = "playback_positions",
                            seed = {
                                db.playbackPositionDao().save(
                                    PlaybackPositionEntity(
                                        bookId = BookId("b1"),
                                        positionMs = 0L,
                                        playbackSpeed = 1.0f,
                                        updatedAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.playbackPositionDao().get(BookId("b1")) == null },
                        ),
                        DomainProbe(
                            domainName = "listening_events",
                            seed = {
                                db.listeningEventDao().upsert(
                                    ListeningEventEntity(
                                        id = "seed-event",
                                        userId = "u1",
                                        bookId = "b1",
                                        startPositionMs = 0L,
                                        endPositionMs = 1000L,
                                        startedAt = 0L,
                                        endedAt = 1000L,
                                        playbackSpeed = 1.0f,
                                        tz = "UTC",
                                        deviceLabel = null,
                                    ),
                                )
                            },
                            isGone = { db.listeningEventDao().getById("seed-event") == null },
                        ),
                        DomainProbe(
                            domainName = "activities",
                            seed = {
                                db.activityDao().upsert(
                                    ActivityEntity(
                                        id = "seed-activity",
                                        userId = "u1",
                                        type = "started_book",
                                        occurredAt = 0L,
                                        bookId = null,
                                        isReread = false,
                                        durationMs = 0L,
                                        milestoneValue = 0,
                                        milestoneUnit = null,
                                        shelfId = null,
                                        shelfName = null,
                                    ),
                                )
                            },
                            isGone = { db.activityDao().getById("seed-activity") == null },
                        ),
                        DomainProbe(
                            domainName = "user_stats",
                            seed = {
                                db.userStatsDao().upsert(
                                    UserStatsEntity(
                                        id = "u1",
                                        totalSecondsAllTime = 0L,
                                        totalSecondsLast7Days = 0L,
                                        totalSecondsLast30Days = 0L,
                                        booksStarted = 0,
                                        booksFinished = 0,
                                        currentStreakDays = 0,
                                        longestStreakDays = 0,
                                        lastEventDate = null,
                                    ),
                                )
                            },
                            isGone = { db.userStatsDao().getForUser("u1") == null },
                        ),
                        DomainProbe(
                            domainName = "public_profiles",
                            seed = {
                                db.publicProfileDao().upsert(
                                    PublicProfileEntity(
                                        id = "u1",
                                        displayName = "n",
                                        avatarType = "auto",
                                        totalSecondsAllTime = 0L,
                                        totalSecondsLast7Days = 0L,
                                        totalSecondsLast30Days = 0L,
                                        totalSecondsLast365Days = 0L,
                                        booksFinished = 0,
                                        currentStreakDays = 0,
                                        longestStreakDays = 0,
                                    ),
                                )
                            },
                            isGone = { db.publicProfileDao().findById("u1") == null },
                        ),
                        DomainProbe(
                            domainName = "series",
                            seed = {
                                db.seriesDao().upsert(
                                    SeriesEntity(
                                        id = SeriesId("seed-series"),
                                        name = "n",
                                        description = null,
                                        createdAt = Timestamp(0L),
                                        updatedAt = Timestamp(0L),
                                    ),
                                )
                            },
                            isGone = { db.seriesDao().getById("seed-series") == null },
                        ),
                        DomainProbe(
                            domainName = "collections",
                            seed = {
                                db.collectionDao().upsert(
                                    CollectionEntity(
                                        id = "seed-collection",
                                        libraryId = "seed-lib",
                                        ownerId = "u1",
                                        name = "n",
                                        isInbox = false,
                                        updatedAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.collectionDao().getById("seed-collection") == null },
                        ),
                        DomainProbe(
                            domainName = "collection_books",
                            seed = {
                                db.collectionBookDao().upsert(
                                    CollectionBookEntity(
                                        collectionId = "seed-collection",
                                        bookId = "b1",
                                        syncId = "seed-collection:b1",
                                        createdAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.collectionBookDao().findByKey("seed-collection", "b1") == null },
                        ),
                        DomainProbe(
                            domainName = "collection_shares",
                            seed = {
                                db.collectionShareDao().upsert(
                                    CollectionShareEntity(
                                        id = "seed-share",
                                        collectionId = "seed-collection",
                                        sharedWithUserId = "u2",
                                        sharedByUserId = "u1",
                                        permission = "read",
                                        updatedAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.collectionShareDao().getById("seed-share") == null },
                        ),
                        DomainProbe(
                            domainName = "contributors",
                            seed = {
                                db.contributorDao().upsert(
                                    ContributorEntity(
                                        id = ContributorId("seed-contributor"),
                                        name = "n",
                                        description = null,
                                        imagePath = null,
                                        createdAt = Timestamp(0L),
                                        updatedAt = Timestamp(0L),
                                    ),
                                )
                            },
                            isGone = { db.contributorDao().getById("seed-contributor") == null },
                        ),
                        DomainProbe(
                            domainName = "books",
                            seed = {
                                db.bookDao().upsert(
                                    BookEntity(
                                        id = BookId("b1"),
                                        libraryId = LibraryId("seed-lib"),
                                        folderId = FolderId("seed-folder"),
                                        title = "n",
                                        totalDuration = 0L,
                                        createdAt = Timestamp(0L),
                                        updatedAt = Timestamp(0L),
                                    ),
                                )
                            },
                            isGone = { db.bookDao().getById(BookId("b1")) == null },
                        ),
                        DomainProbe(
                            domainName = "admin_user_roster",
                            seed = {
                                db.adminUserRosterDao().upsert(
                                    AdminUserRosterEntity(
                                        id = "seed-roster-user",
                                        email = "e@x.test",
                                        displayName = "n",
                                        role = "user",
                                        status = "active",
                                        canShare = false,
                                        accountCreatedAt = 0L,
                                    ),
                                )
                            },
                            isGone = { db.adminUserRosterDao().findById("seed-roster-user") == null },
                        ),
                    )

                probes.map { it.domainName }.toSet() shouldBe catalog.mirrored.map { it.key.name }.toSet()

                for (probe in probes) probe.seed()
                for (probe in probes) withClue(probe.domainName) { probe.isGone().shouldBeFalse() }

                val helper =
                    LibraryResetHelperImpl(
                        database = db,
                        transactionRunner = RoomTransactionRunner(db),
                        librarySyncContract = mock<LibrarySync>(),
                    )
                helper.clearLibraryData(discardPendingOperations = true)

                for (probe in probes) withClue(probe.domainName) { probe.isGone().shouldBeTrue() }
            } finally {
                db.close()
            }
        }
    })

/** One mirrored domain's seed action + post-clear presence check, keyed by wire name. */
private class DomainProbe(
    val domainName: String,
    val seed: suspend () -> Unit,
    val isGone: suspend () -> Boolean,
)
