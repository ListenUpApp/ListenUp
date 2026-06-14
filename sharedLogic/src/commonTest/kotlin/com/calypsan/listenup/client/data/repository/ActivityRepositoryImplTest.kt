package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.dto.activity.ActivityEvent
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityEntity
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.data.remote.ActivityRpcFactory
import com.calypsan.listenup.client.presentation.profile.stableAvatarColorHex
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for ActivityRepositoryImpl.
 *
 * Read path (Room): `observeRecent`, `getOlderThan`, `getNewestTimestamp` are unchanged —
 * the local cache is the offline-first read source.
 *
 * Write path (RPC → Room): `fetchAndCacheActivities` now drives [ActivityService.feed] via
 * [ActivityRpcFactory], maps each [ActivityEvent] to a domain [Activity] (identity from the DTO,
 * `avatarColor` from [stableAvatarColorHex], book card enriched from local Room via
 * [BookDao.getBookSummary]), upserts into Room, and returns the count as an [AppResult].
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
class ActivityRepositoryImplTest :
    FunSpec({
        // ========== Test Fixtures ==========

        fun createMockDao(): ActivityDao = mock<ActivityDao>()

        fun createMockBookDao(): BookDao = mock<BookDao>()

        // A fake [ActivityRpcFactory] that always returns the supplied [service].
        fun fakeRpcFactory(service: ActivityService): ActivityRpcFactory =
            object : ActivityRpcFactory {
                override suspend fun get(): ActivityService = service

                override suspend fun invalidate() = Unit
            }

        fun createRepository(
            dao: ActivityDao = createMockDao(),
            rpc: ActivityRpcFactory = fakeRpcFactory(mock<ActivityService>()),
            bookDao: BookDao = createMockBookDao(),
        ): ActivityRepositoryImpl = ActivityRepositoryImpl(dao = dao, activityRpc = rpc, bookDao = bookDao)

        // ========== Test Data Factories ==========

        fun bookEvent(
            id: String = "event-book",
            userId: String = "11111111-1111-1111-1111-111111111111",
            bookId: String = "book-1",
        ): ActivityEvent =
            ActivityEvent(
                id = id,
                userId = userId,
                displayName = "John Smith",
                avatarType = "auto",
                type = ActivityType.FINISHED_BOOK,
                createdAtMs = 1704067200000L,
                bookId = bookId,
                isReread = true,
                durationMs = 3600000L,
                milestoneValue = 0,
                milestoneUnit = null,
            )

        fun shelfEvent(
            id: String = "event-shelf",
            userId: String = "22222222-2222-2222-2222-222222222222",
        ): ActivityEvent =
            ActivityEvent(
                id = id,
                userId = userId,
                displayName = "Jane Doe",
                avatarType = "auto",
                type = ActivityType.SHELF_CREATED,
                createdAtMs = 1704000000000L,
                shelfId = "shelf-1",
                shelfName = "Fantasy Favorites",
            )

        fun bookSummary(id: String = "book-1"): BookSummary =
            BookSummary(
                id = id,
                title = "The Way of Kings",
                coverBlurHash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
                authorName = "Brandon Sanderson",
            )

        fun createActivityEntity(
            id: String = "activity-1",
            bookId: String? = "book-1",
            bookTitle: String? = "The Way of Kings",
        ): ActivityEntity =
            ActivityEntity(
                id = id,
                userId = "user-1",
                type = "finished_book",
                createdAt = 1704067200000L,
                userDisplayName = "John Smith",
                userAvatarColor = "#FF5733",
                userAvatarType = "auto",
                userAvatarValue = null,
                bookId = bookId,
                bookTitle = bookTitle,
                bookAuthorName = "Brandon Sanderson",
                bookCoverPath = null,
                isReread = false,
                durationMs = 3600000L,
                milestoneValue = 0,
                milestoneUnit = null,
                shelfId = null,
                shelfName = null,
            )

        // ========== fetchAndCacheActivities (RPC → Room) ==========

        test("fetchAndCacheActivities maps events to activities and upserts them") {
            runTest {
                // Given - feed yields a book-bearing event and a shelf_created event
                val dao = createMockDao()
                everySuspend { dao.upsertAll(any()) } returns Unit
                val bookDao = createMockBookDao()
                everySuspend { bookDao.getBookSummary("book-1") } returns bookSummary()

                val service =
                    mock<ActivityService> {
                        everySuspend { feed(any(), any()) } returns
                            AppResult.Success(listOf(bookEvent(), shelfEvent()))
                    }
                val repository = createRepository(dao = dao, rpc = fakeRpcFactory(service), bookDao = bookDao)

                // When
                val result = repository.fetchAndCacheActivities(limit = 50)

                // Then - success with the mapped count
                val success = result.shouldBeInstanceOf<AppResult.Success<Int>>()
                success.data shouldBe 2

                // And the head was fetched (before = null)
                verifySuspend { service.feed(null, 50) }

                // And the mapped entities were persisted
                verifySuspend { dao.upsertAll(any()) }
            }
        }

        test("fetchAndCacheActivities maps identity and avatar colour from the DTO") {
            runTest {
                // Given
                val dao = createMockDao()
                val captured = mutableListOf<ActivityEntity>()
                everySuspend { dao.upsertAll(any()) } calls { args ->
                    @Suppress("UNCHECKED_CAST")
                    captured.addAll(args.arg(0) as List<ActivityEntity>)
                }
                val bookDao = createMockBookDao()
                everySuspend { bookDao.getBookSummary("book-1") } returns bookSummary()

                val event = bookEvent()
                val service =
                    mock<ActivityService> {
                        everySuspend { feed(any(), any()) } returns AppResult.Success(listOf(event))
                    }
                val repository = createRepository(dao = dao, rpc = fakeRpcFactory(service), bookDao = bookDao)

                // When
                repository.fetchAndCacheActivities(limit = 50)

                // Then - identity from the DTO, avatarColor derived, avatarValue null
                val entity = captured.single()
                entity.userDisplayName shouldBe "John Smith"
                entity.userAvatarType shouldBe "auto"
                entity.userAvatarColor shouldBe stableAvatarColorHex(event.userId)
                entity.userAvatarValue shouldBe null
                // Book card enriched from local Room
                entity.bookId shouldBe "book-1"
                entity.bookTitle shouldBe "The Way of Kings"
                entity.bookAuthorName shouldBe "Brandon Sanderson"
                // DTO-borne activity fields preserved
                entity.isReread shouldBe true
                entity.durationMs shouldBe 3600000L
            }
        }

        test("fetchAndCacheActivities leaves book null for non-book events") {
            runTest {
                // Given - only the shelf event (no bookId)
                val dao = createMockDao()
                val captured = mutableListOf<ActivityEntity>()
                everySuspend { dao.upsertAll(any()) } calls { args ->
                    @Suppress("UNCHECKED_CAST")
                    captured.addAll(args.arg(0) as List<ActivityEntity>)
                }
                val bookDao = createMockBookDao()

                val service =
                    mock<ActivityService> {
                        everySuspend { feed(any(), any()) } returns AppResult.Success(listOf(shelfEvent()))
                    }
                val repository = createRepository(dao = dao, rpc = fakeRpcFactory(service), bookDao = bookDao)

                // When
                repository.fetchAndCacheActivities(limit = 50)

                // Then - no book card, shelf fields from the DTO
                val entity = captured.single()
                entity.bookId shouldBe null
                entity.bookTitle shouldBe null
                entity.shelfId shouldBe "shelf-1"
                entity.shelfName shouldBe "Fantasy Favorites"
            }
        }

        test("fetchAndCacheActivities returns Failure when the RPC throws") {
            runTest {
                // Given - feed throws a non-cancellation error
                val service =
                    mock<ActivityService> {
                        everySuspend { feed(any(), any()) } throws RuntimeException("rpc down")
                    }
                val repository = createRepository(rpc = fakeRpcFactory(service))

                // When
                val result = repository.fetchAndCacheActivities(limit = 50)

                // Then - a typed Failure, NOT a thrown exception
                result.shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("fetchAndCacheActivities re-raises CancellationException") {
            runTest {
                // Given - feed throws CancellationException (structured concurrency must propagate)
                val service =
                    mock<ActivityService> {
                        everySuspend { feed(any(), any()) } throws CancellationException("cancelled")
                    }
                val repository = createRepository(rpc = fakeRpcFactory(service))

                // When / Then - the cancellation propagates rather than being swallowed
                shouldThrow<CancellationException> {
                    repository.fetchAndCacheActivities(limit = 50)
                }
            }
        }

        // ========== observeRecent (Room read path — unchanged) ==========

        test("observeRecent returns activities from dao") {
            runTest {
                // Given
                val dao = createMockDao()
                val entities = listOf(createActivityEntity(id = "act-1"), createActivityEntity(id = "act-2"))
                every { dao.observeRecent(10) } returns flowOf(entities)
                val repository = createRepository(dao = dao)

                // When
                val result = repository.observeRecent(10).first()

                // Then
                result.size shouldBe 2
                result[0].id shouldBe "act-1"
                result[1].id shouldBe "act-2"
            }
        }

        test("observeRecent emits updates when flow updates") {
            runTest {
                // Given
                val dao = createMockDao()
                val flowSource = MutableStateFlow<List<ActivityEntity>>(emptyList())
                every { dao.observeRecent(any()) } returns flowSource
                val repository = createRepository(dao = dao)

                // When - initial emission
                repository.observeRecent(10).first().isEmpty() shouldBe true

                // When - flow updates
                flowSource.value = listOf(createActivityEntity(id = "new-activity"))

                // Then - updated list
                val result2 = repository.observeRecent(10).first()
                result2.size shouldBe 1
                result2[0].id shouldBe "new-activity"
            }
        }

        test("observeRecent maps entity book card to domain") {
            runTest {
                // Given
                val dao = createMockDao()
                every { dao.observeRecent(any()) } returns flowOf(listOf(createActivityEntity(bookId = "book-9", bookTitle = "Mistborn")))
                val repository = createRepository(dao = dao)

                // When
                val activity = repository.observeRecent(10).first().first()

                // Then
                activity.book.shouldNotBeNull()
                activity.book?.id shouldBe "book-9"
                activity.book?.title shouldBe "Mistborn"
            }
        }

        // ========== getOlderThan / getNewestTimestamp (Room) ==========

        test("getOlderThan returns activities from dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getOlderThan(1704067200000L, 10) } returns listOf(createActivityEntity(id = "old-1"))
                val repository = createRepository(dao = dao)

                // When
                val result = repository.getOlderThan(beforeMs = 1704067200000L, limit = 10)

                // Then
                result.size shouldBe 1
                result[0].id shouldBe "old-1"
            }
        }

        test("getNewestTimestamp returns timestamp from dao") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getNewestTimestamp() } returns 1704067200000L
                val repository = createRepository(dao = dao)

                // When / Then
                repository.getNewestTimestamp() shouldBe 1704067200000L
            }
        }
    })
