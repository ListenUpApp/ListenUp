package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map

/**
 * Contributor lists and counts, Android Auto's author shelf, and Discover's "others listening" all
 * ignore held books on an admin's device — and pick them back up once the release echo lands.
 */
class ContributorAndSessionHeldExclusionTest :
    FunSpec({
        // "ann" wrote a visible and a held book; "hal" wrote ONLY the held one.
        suspend fun seedAuthors(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible")
            HeldBookFixture.seedBook(db, "held")
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
            listOf("ann" to "Ann", "hal" to "Hal").forEach { (id, name) ->
                db.contributorDao().upsert(
                    ContributorEntity(
                        id = ContributorId(id),
                        name = name,
                        description = null,
                        imagePath = null,
                        createdAt = Timestamp(0L),
                        updatedAt = Timestamp(0L),
                    ),
                )
            }
            listOf("ann" to "visible", "ann" to "held", "hal" to "held").forEach { (contributor, book) ->
                db.bookContributorDao().insert(
                    BookContributorCrossRef(bookId = BookId(book), contributorId = ContributorId(contributor), role = "author"),
                )
            }
        }

        test("observeByRoleWithCount — the Authors tab drops a held-only author and counts honestly") {
            withHeldBookDb { db ->
                seedAuthors(db)
                db
                    .contributorDao()
                    .observeByRoleWithCount("author")
                    .map { rows -> rows.associate { it.contributor.id.value to it.bookCount } }
                    .test {
                        awaitItem() shouldBe mapOf("ann" to 1)
                        HeldBookFixture.applyReleaseEcho(db, "held")
                        awaitItemMatching { "hal" in it } shouldBe mapOf("ann" to 2, "hal" to 1)
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("observeRolesWithCountForContributor — contributor detail counts") {
            withHeldBookDb { db ->
                seedAuthors(db)
                db
                    .contributorDao()
                    .observeRolesWithCountForContributor("ann")
                    .map { rows -> rows.associate { it.role to it.bookCount } }
                    .test {
                        awaitItem() shouldBe mapOf("author" to 1)
                        HeldBookFixture.applyReleaseEcho(db, "held")
                        awaitItemMatching { it["author"] == 2 } shouldBe mapOf("author" to 2)
                        cancelAndIgnoreRemainingEvents()
                    }
            }
        }

        test("getBookIdsForContributor — Android Auto's author shelf") {
            withHeldBookDb { db ->
                seedAuthors(db)
                db.contributorDao().getBookIdsForContributor("ann") shouldContainExactly listOf("visible")

                HeldBookFixture.applyReleaseEcho(db, "held")

                db.contributorDao().getBookIdsForContributor("ann") shouldContainExactlyInAnyOrder listOf("visible", "held")
            }
        }

        test("observeWithBooks — Discover's others-listening roster") {
            withHeldBookDb { db ->
                HeldBookFixture.seedBook(db, "visible")
                HeldBookFixture.seedBook(db, "held")
                HeldBookFixture.publish(db, "visible")
                HeldBookFixture.hold(db, "held")
                db.cachedActiveSessionDao().upsertAll(
                    listOf("visible" to "u1", "held" to "u2").map { (book, user) ->
                        CachedActiveSessionEntity(
                            userId = user,
                            displayName = "Listener $user",
                            avatarType = "auto",
                            bookId = book,
                            lastActiveAtMs = 1L,
                            isLive = true,
                            observedAt = 1L,
                        )
                    },
                )
                db.cachedActiveSessionDao().observeWithBooks().map { rows -> rows.map { it.session.bookId } }.test {
                    awaitItem() shouldContainExactly listOf("visible")
                    HeldBookFixture.applyReleaseEcho(db, "held")
                    awaitItemMatching { "held" in it } shouldContainExactlyInAnyOrder listOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })
