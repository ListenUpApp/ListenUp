package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.core.stableAvatarColorHex
import com.calypsan.listenup.client.data.local.db.ActivityDao
import com.calypsan.listenup.client.data.local.db.ActivityWithProfile
import dev.mokkery.answering.returns
import dev.mokkery.every
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
 * the sync data channel; this repository only READS and maps each fully-enriched
 * [ActivityWithProfile] row into a domain `Activity`: identity (display name, avatar type) comes
 * from the joined `public_profiles` columns, `avatarColor` is derived locally via
 * [stableAvatarColorHex], `avatarValue` is always null (public profiles carry none), and the book
 * card is built entirely from the joined `books` columns (`bookTitle`/`bookCoverPath`/
 * `bookAuthorName`) — present only when the row has a book that is accessible locally. There is no
 * per-row book lookup: the DAO join is the single enrichment seam.
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
class ActivityRepositoryImplTest :
    FunSpec({
        // ========== Test Fixtures ==========

        fun createMockDao(): ActivityDao = mock<ActivityDao>()

        fun createRepository(dao: ActivityDao = createMockDao()): ActivityRepositoryImpl = ActivityRepositoryImpl(dao = dao)

        // ========== Test Data Factories ==========

        fun activityRow(
            id: String = "activity-1",
            userId: String = "11111111-1111-1111-1111-111111111111",
            bookId: String? = "book-1",
            displayName: String? = "John Smith",
            avatarType: String? = "auto",
            bookTitle: String? = "The Way of Kings",
            bookCoverPath: String? = "LKO2?U%2Tw=w]~RBVZRi};RPxuwH",
            bookAuthorName: String? = "Brandon Sanderson",
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
                bookTitle = bookTitle,
                bookCoverPath = bookCoverPath,
                bookAuthorName = bookAuthorName,
            )

        // ========== observeRecent (Room read + enrichment) ==========

        test("observeRecent enriches identity from the joined profile and the book card from the joined columns") {
            runTest {
                // Given - a book-bearing, fully-joined row
                val dao = createMockDao()
                val row = activityRow()
                every { dao.observeRecent(10) } returns flowOf(listOf(row))
                val repository = createRepository(dao = dao)

                // When
                val activity = repository.observeRecent(10).first().single()

                // Then - identity from the joined profile, colour derived, value null
                activity.id shouldBe "activity-1"
                activity.user.displayName shouldBe "John Smith"
                activity.user.avatarType shouldBe "auto"
                activity.user.avatarColor shouldBe stableAvatarColorHex(row.userId)
                activity.user.avatarValue.shouldBeNull()
                // Book card built from the joined columns
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
                val row =
                    activityRow(
                        bookId = null,
                        displayName = null,
                        avatarType = null,
                        bookTitle = null,
                        bookCoverPath = null,
                        bookAuthorName = null,
                    )
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

        test("observeRecent yields a null book card when the book is inaccessible (join produced no title)") {
            runTest {
                // Given - a row with a bookId but no joined book (deleted/inaccessible locally: title null)
                val dao = createMockDao()
                val row = activityRow(bookTitle = null, bookCoverPath = null, bookAuthorName = null)
                every { dao.observeRecent(any()) } returns flowOf(listOf(row))
                val repository = createRepository(dao = dao)

                // When
                val activity = repository.observeRecent(10).first().single()

                // Then - no card even though bookId is present, because the book row didn't join
                activity.book.shouldBeNull()
            }
        }

        test("observeRecent emits updates when the underlying flow updates") {
            runTest {
                // Given
                val dao = createMockDao()
                val flowSource = MutableStateFlow<List<ActivityWithProfile>>(emptyList())
                every { dao.observeRecent(any()) } returns flowSource
                val repository = createRepository(dao = dao)

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
    })
