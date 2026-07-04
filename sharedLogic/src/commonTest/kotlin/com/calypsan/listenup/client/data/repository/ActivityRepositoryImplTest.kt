package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityWithProfile
import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.BookSummary
import com.calypsan.listenup.client.presentation.profile.stableAvatarColorHex
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest

/**
 * Tests for [ActivityRepositoryImpl].
 *
 * The repository is a pure Room read seam over the cursored `activities` mirror. Writes arrive on
 * the sync data channel; this repository only READS and enriches each joined [ActivityWithProfile]
 * row into a domain `Activity`: identity (display name, avatar type) comes from the joined
 * `public_profiles` row, `avatarColor` is derived locally via [stableAvatarColorHex], `avatarValue`
 * is always null (public profiles carry none), and the book card is reconstructed from the local
 * book mirror via [BookDao.getBookSummary].
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
class ActivityRepositoryImplTest :
    FunSpec({
        // ========== Test Fixtures ==========

        fun createMockDao(): ActivityDao = mock<ActivityDao>()

        fun createMockBookDao(): BookDao = mock<BookDao>()

        fun createRepository(
            dao: ActivityDao = createMockDao(),
            bookDao: BookDao = createMockBookDao(),
        ): ActivityRepositoryImpl = ActivityRepositoryImpl(dao = dao, bookDao = bookDao)

        // ========== Test Data Factories ==========

        fun bookSummary(id: String = "book-1"): BookSummary =
            BookSummary(
                id = id,
                title = "The Way of Kings",
                coverBlurHash = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
                coverHash = null,
                authorName = "Brandon Sanderson",
            )

        fun activityRow(
            id: String = "activity-1",
            userId: String = "11111111-1111-1111-1111-111111111111",
            bookId: String? = "book-1",
            displayName: String? = "John Smith",
            avatarType: String? = "auto",
        ): ActivityWithProfile =
            ActivityWithProfile(
                id = id,
                userId = userId,
                type = "finished_book",
                occurredAt = 1704067200000L,
                bookId = bookId,
                isReread = true,
                durationMs = 3600000L,
                milestoneValue = 0,
                milestoneUnit = null,
                shelfId = null,
                shelfName = null,
                displayName = displayName,
                avatarType = avatarType,
            )

        // ========== observeRecent (Room read + enrichment) ==========

        test("observeRecent enriches identity from the joined profile and the book card from the mirror") {
            runTest {
                // Given - a book-bearing row and its local book summary
                val dao = createMockDao()
                val bookDao = createMockBookDao()
                val row = activityRow()
                every { dao.observeRecent(10) } returns flowOf(listOf(row))
                everySuspend { bookDao.getBookSummary("book-1") } returns bookSummary()
                val repository = createRepository(dao = dao, bookDao = bookDao)

                // When
                val activity = repository.observeRecent(10).first().single()

                // Then - identity from the joined profile, colour derived, value null
                activity.id shouldBe "activity-1"
                activity.user.displayName shouldBe "John Smith"
                activity.user.avatarType shouldBe "auto"
                activity.user.avatarColor shouldBe stableAvatarColorHex(row.userId)
                activity.user.avatarValue.shouldBeNull()
                // Book card reconstructed from the local mirror (blur hash stands in for the cover)
                activity.book.shouldNotBeNull()
                activity.book?.id shouldBe "book-1"
                activity.book?.title shouldBe "The Way of Kings"
                activity.book?.authorName shouldBe "Brandon Sanderson"
                activity.book?.coverPath shouldBe "LKO2?U%2Tw=w]~RBVZRi};RPxuwH"
                // Raw activity fields carried through
                activity.isReread shouldBe true
                activity.durationMs shouldBe 3600000L
            }
        }

        test("observeRecent falls back to empty name and 'auto' avatar when the profile is not mirrored") {
            runTest {
                // Given - the author's public profile is not yet mirrored (LEFT JOIN yields nulls)
                val dao = createMockDao()
                val row = activityRow(bookId = null, displayName = null, avatarType = null)
                every { dao.observeRecent(any()) } returns flowOf(listOf(row))
                val repository = createRepository(dao = dao)

                // When
                val activity = repository.observeRecent(10).first().single()

                // Then - graceful fallbacks; no book card for a bookless row
                activity.user.displayName shouldBe ""
                activity.user.avatarType shouldBe "auto"
                activity.book.shouldBeNull()
            }
        }

        test("observeRecent emits updates when the underlying flow updates") {
            runTest {
                // Given
                val dao = createMockDao()
                val bookDao = createMockBookDao()
                everySuspend { bookDao.getBookSummary(any()) } returns bookSummary()
                val flowSource = MutableStateFlow<List<ActivityWithProfile>>(emptyList())
                every { dao.observeRecent(any()) } returns flowSource
                val repository = createRepository(dao = dao, bookDao = bookDao)

                // When - initial emission is empty
                repository.observeRecent(10).first().isEmpty() shouldBe true

                // When - the flow updates
                flowSource.value = listOf(activityRow(id = "new-activity"))

                // Then - the updated list is enriched and re-emitted
                val result = repository.observeRecent(10).first()
                result.size shouldBe 1
                result[0].id shouldBe "new-activity"
            }
        }

        // ========== getOlderThan / getNewestTimestamp / count (Room) ==========

        test("getOlderThan enriches older rows from the dao") {
            runTest {
                // Given
                val dao = createMockDao()
                val bookDao = createMockBookDao()
                everySuspend { bookDao.getBookSummary("book-1") } returns bookSummary()
                everySuspend { dao.getOlderThan(1704067200000L, 10) } returns listOf(activityRow(id = "old-1"))
                val repository = createRepository(dao = dao, bookDao = bookDao)

                // When
                val result = repository.getOlderThan(beforeMs = 1704067200000L, limit = 10)

                // Then
                result.size shouldBe 1
                result[0].id shouldBe "old-1"
                result[0].book?.title shouldBe "The Way of Kings"
            }
        }

        test("getNewestTimestamp passes through the dao value") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.getNewestTimestamp() } returns 1704067200000L
                val repository = createRepository(dao = dao)

                // When / Then
                repository.getNewestTimestamp() shouldBe 1704067200000L
            }
        }

        test("count passes through the dao value") {
            runTest {
                // Given
                val dao = createMockDao()
                everySuspend { dao.count() } returns 7
                val repository = createRepository(dao = dao)

                // When / Then
                repository.count() shouldBe 7
            }
        }
    })
