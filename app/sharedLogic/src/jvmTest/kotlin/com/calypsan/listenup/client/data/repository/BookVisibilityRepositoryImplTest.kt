package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.local.db.HeldBookFixture.ALL_BOOKS
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.applyReleaseEcho
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.hold
import com.calypsan.listenup.client.data.local.db.HeldBookFixture.membership
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.awaitItemMatching
import com.calypsan.listenup.client.data.local.db.withHeldBookDb
import com.calypsan.listenup.client.domain.model.BookVisibility
import com.calypsan.listenup.client.domain.model.CollectionRef
import com.calypsan.listenup.client.domain.model.HiddenFrom
import com.calypsan.listenup.client.test.collectionShare
import com.calypsan.listenup.client.test.fake.FakeUserRepository
import com.calypsan.listenup.client.test.normalCollection
import com.calypsan.listenup.client.test.rosterUser
import com.calypsan.listenup.core.BookId
import app.cash.turbine.test
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first

/**
 * [BookVisibilityRepositoryImpl] over a real in-memory Room: the admin gate, live updates as
 * shares come and go, the tile set agreeing with the per-book classification, and Held agreeing
 * with the inbox's held set.
 */
class BookVisibilityRepositoryImplTest :
    FunSpec({
        fun kids(hiddenFrom: HiddenFrom) = BookVisibility.Restricted(listOf(CollectionRef("c1", "Kids")), hiddenFrom)

        suspend fun seed(db: ListenUpDatabase) {
            db.collectionDao().upsert(normalCollection("c1", "Kids"))
            listOf(
                rosterUser("root", "Root", role = "ROOT"),
                rosterUser("alice", "Alice"),
                rosterUser("bob", "bob"),
            ).forEach { db.adminUserRosterDao().upsert(it) }
        }

        fun repo(
            db: ListenUpDatabase,
            users: FakeUserRepository,
        ) = BookVisibilityRepositoryImpl(
            collectionDao = db.collectionDao(),
            collectionBookDao = db.collectionBookDao(),
            collectionShareDao = db.collectionShareDao(),
            adminUserRosterDao = db.adminUserRosterDao(),
            userRepository = users,
        )

        test("on a member's device there is no visibility and nothing is restricted") {
            withHeldBookDb { db ->
                seed(db)
                val repo = repo(db, FakeUserRepository(initialIsAdmin = false))
                db.collectionBookDao().upsert(membership("c1", "b1"))

                repo.observeBookVisibility(BookId("b1")).first() shouldBe null
                repo.observeRestrictedBookIds().first() shouldBe emptySet()
            }
        }

        test("adding and revoking shares updates the visibility live") {
            withHeldBookDb { db ->
                seed(db)
                val repo = repo(db, FakeUserRepository(initialIsAdmin = true))
                db.collectionBookDao().upsert(membership("c1", "b1"))

                repo.observeBookVisibility(BookId("b1")).test {
                    awaitItemMatching { it == kids(HiddenFrom.Everyone) }

                    db.collectionShareDao().upsert(collectionShare("c1", "alice"))
                    awaitItemMatching { it == kids(HiddenFrom.Members(listOf("bob"))) }

                    db.collectionShareDao().upsert(collectionShare("c1", "bob"))
                    awaitItemMatching { it == kids(HiddenFrom.Nobody) }

                    db.collectionShareDao().softDelete("c1:alice", deletedAt = 50L, revision = 2L)
                    awaitItemMatching { it == kids(HiddenFrom.Members(listOf("Alice"))) }

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("becoming an admin switches visibility on, live") {
            withHeldBookDb { db ->
                seed(db)
                val users = FakeUserRepository(initialIsAdmin = false)
                val repo = repo(db, users)
                db.collectionBookDao().upsert(membership(ALL_BOOKS, "b1"))

                repo.observeBookVisibility(BookId("b1")).test {
                    awaitItem() shouldBe null
                    users.isAdmin.value = true
                    awaitItemMatching { it == BookVisibility.Public }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a release echo turns Held into Public, live") {
            withHeldBookDb { db ->
                seed(db)
                val repo = repo(db, FakeUserRepository(initialIsAdmin = true))
                hold(db, "b1")

                repo.observeBookVisibility(BookId("b1")).test {
                    awaitItemMatching { it == BookVisibility.Held }
                    applyReleaseEcho(db, "b1")
                    awaitItemMatching { it == BookVisibility.Public }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the restricted set is exactly the books classified Restricted, and Held is exactly the inbox's held set") {
            withHeldBookDb { db ->
                seed(db)
                val repo = repo(db, FakeUserRepository(initialIsAdmin = true))
                db.collectionDao().upsert(normalCollection("dead", "Old", deletedAt = 9L))
                val members = db.collectionBookDao()
                members.upsert(membership(ALL_BOOKS, "b-public"))
                members.upsert(membership("c1", "b-restricted"))
                hold(db, "b-held")
                hold(db, "b-held-curated")
                members.upsert(membership("c1", "b-held-curated"))
                members.upsert(membership("c1", "b-left", deletedAt = 5L))
                members.upsert(membership("dead", "b-dead"))
                val bookIds = listOf("b-public", "b-restricted", "b-held", "b-held-curated", "b-left", "b-dead", "b-never")

                val restricted = repo.observeRestrictedBookIds().first()
                val held = members.observeHeldBookIds().first().toSet()
                restricted shouldBe setOf(BookId("b-restricted"))
                for (id in bookIds) {
                    val visibility = repo.observeBookVisibility(BookId(id)).first()
                    (visibility is BookVisibility.Restricted) shouldBe (BookId(id) in restricted)
                    (visibility == BookVisibility.Held) shouldBe (id in held)
                }
                repo.observeBookVisibility(BookId("b-dead")).first() shouldBe BookVisibility.Stranded
                repo.observeBookVisibility(BookId("b-never")).first() shouldBe BookVisibility.Stranded
            }
        }
    })
