package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.CollectionBookEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.MoodEntity
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.repository.MoodRepositoryImpl
import com.calypsan.listenup.client.data.repository.TagRepositoryImpl
import com.calypsan.listenup.client.data.sync.domains.DigestParticipation
import com.calypsan.listenup.client.data.sync.domains.MirroredDomain
import com.calypsan.listenup.client.data.sync.domains.bookMoodsDomain
import com.calypsan.listenup.client.data.sync.domains.bookTagsDomain
import com.calypsan.listenup.client.data.sync.domains.collectionBooksDomain
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * A row the user added while the server had not yet answered is written locally at `revision = 0`.
 * The server cannot have it, so counting it in the domain's digest would make the domain read as
 * drifted — and re-pulled from zero — until the add drains. These specs pin that, per domain, for the
 * junction domains whose optimistic write precedes any server acknowledgement.
 */
class QueuedAddDigestTest :
    FunSpec({

        fun offlineEditor(db: ListenUpDatabase): OfflineEditor =
            OfflineEditor(
                pendingQueue =
                    PendingOperationQueue(
                        dao = db.pendingOperationV2Dao(),
                        sender = PendingOperationSender { AppResult.Success(Unit) },
                    ),
                transactionRunner =
                    object : TransactionRunner {
                        override suspend fun <R> atomically(block: suspend () -> R): R = block()
                    },
                authSession = FakeAuthSession(userId = "u1"),
            )

        suspend fun digestOf(domain: MirroredDomain<*>): DomainDigest {
            val full = domain.digest.shouldBeInstanceOf<DigestParticipation.Full>()
            return DigestComputer.compute(Long.MAX_VALUE, full.rows(Long.MAX_VALUE))
        }

        test("book_tags: a tag added through the repository does not change the domain digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.tagDao().upsert(TagEntity(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 4, updatedAt = 0L))
                    db.bookTagDao().upsert(BookTagEntity(bookId = "b1", tagId = "t1", syncId = "s-b1-t1", createdAt = 0L, revision = 7))
                    val domain = bookTagsDomain(db)
                    val acknowledgedOnly = digestOf(domain)
                    acknowledgedOnly.count shouldBe 1

                    val repo =
                        TagRepositoryImpl(
                            channel = RpcChannel.forTest(mock<TagService>()),
                            tagDao = db.tagDao(),
                            bookTagDao = db.bookTagDao(),
                            offlineEditor = offlineEditor(db),
                        )
                    repo.addTagToBook(bookId = "b2", name = "sci-fi").shouldBeInstanceOf<AppResult.Success<*>>()
                    db.bookTagDao().findByKey("b2", "t1")!!.revision shouldBe 0L

                    digestOf(domain) shouldBe acknowledgedOnly
                } finally {
                    db.close()
                }
            }
        }

        test("book_tags: an acknowledged row changing revision still changes the digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.bookTagDao().upsert(BookTagEntity(bookId = "b1", tagId = "t1", syncId = "s-b1-t1", createdAt = 0L, revision = 7))
                    val before = digestOf(bookTagsDomain(db))
                    db.bookTagDao().upsert(BookTagEntity(bookId = "b1", tagId = "t1", syncId = "s-b1-t1", createdAt = 0L, revision = 8))
                    digestOf(bookTagsDomain(db)) shouldNotBe before
                } finally {
                    db.close()
                }
            }
        }

        test("book_moods: a mood added through the repository does not change the domain digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.moodDao().upsert(MoodEntity(id = "m1", name = "Feel-Good", slug = "feel-good", revision = 4, updatedAt = 0L))
                    db.bookMoodDao().upsert(BookMoodEntity(bookId = "b1", moodId = "m1", syncId = "s-b1-m1", createdAt = 0L, revision = 7))
                    val domain = bookMoodsDomain(db)
                    val acknowledgedOnly = digestOf(domain)
                    acknowledgedOnly.count shouldBe 1

                    val repo =
                        MoodRepositoryImpl(
                            channel = RpcChannel.forTest(mock<MoodService>()),
                            moodDao = db.moodDao(),
                            bookMoodDao = db.bookMoodDao(),
                            offlineEditor = offlineEditor(db),
                        )
                    repo.addMoodToBook(bookId = "b2", name = "feel-good").shouldBeInstanceOf<AppResult.Success<*>>()
                    db.bookMoodDao().findByKey("b2", "m1")!!.revision shouldBe 0L

                    digestOf(domain) shouldBe acknowledgedOnly
                } finally {
                    db.close()
                }
            }
        }

        test("book_moods: an acknowledged row changing revision still changes the digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.bookMoodDao().upsert(BookMoodEntity(bookId = "b1", moodId = "m1", syncId = "s-b1-m1", createdAt = 0L, revision = 7))
                    val before = digestOf(bookMoodsDomain(db))
                    db.bookMoodDao().upsert(BookMoodEntity(bookId = "b1", moodId = "m1", syncId = "s-b1-m1", createdAt = 0L, revision = 8))
                    digestOf(bookMoodsDomain(db)) shouldNotBe before
                } finally {
                    db.close()
                }
            }
        }

        test("collection_books: a queued add does not change the domain digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.collectionBookDao().upsert(
                        CollectionBookEntity(collectionId = "c1", bookId = "b1", syncId = "s-c1-b1", createdAt = 0L, revision = 7),
                    )
                    val domain = collectionBooksDomain(db)
                    val acknowledgedOnly = digestOf(domain)
                    acknowledgedOnly.count shouldBe 1

                    // The shape CollectionRepositoryImpl.addBook writes before the server answers.
                    db.collectionBookDao().upsert(
                        CollectionBookEntity(collectionId = "c1", bookId = "b2", syncId = "s-c1-b2", createdAt = 0L, revision = 0),
                    )

                    digestOf(domain) shouldBe acknowledgedOnly
                } finally {
                    db.close()
                }
            }
        }

        test("collection_books: an acknowledged row changing revision still changes the digest") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.collectionBookDao().upsert(
                        CollectionBookEntity(collectionId = "c1", bookId = "b1", syncId = "s-c1-b1", createdAt = 0L, revision = 7),
                    )
                    val before = digestOf(collectionBooksDomain(db))
                    db.collectionBookDao().upsert(
                        CollectionBookEntity(collectionId = "c1", bookId = "b1", syncId = "s-c1-b1", createdAt = 0L, revision = 8),
                    )
                    digestOf(collectionBooksDomain(db)) shouldNotBe before
                } finally {
                    db.close()
                }
            }
        }
    })
