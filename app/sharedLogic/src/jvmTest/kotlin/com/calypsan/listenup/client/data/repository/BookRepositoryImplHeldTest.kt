package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.client.data.local.db.HeldBookFixture
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.awaitItemMatching
import com.calypsan.listenup.client.data.local.db.withHeldBookDb
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.domain.repository.ImageStorage
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.TagRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * [BookRepositoryImpl] over real Room: held books are triage-only, so every book list it serves —
 * the library list, the id-addressed list (Continue Listening, browse, Hardcover settings) and book
 * search (iOS App Intents) — leaves them out, and shows them again once the release echo lands.
 */
class BookRepositoryImplHeldTest :
    FunSpec({
        fun repository(db: ListenUpDatabase): BookRepositoryImpl {
            val genreRepository =
                mock<GenreRepository> { every { observeGenresForBook(any()) } returns MutableStateFlow(emptyList()) }
            val tagRepository =
                mock<TagRepository> { every { observeTagsForBook(any()) } returns MutableStateFlow(emptyList()) }
            val moodRepository =
                mock<MoodRepository> { every { observeMoodsForBook(any()) } returns MutableStateFlow(emptyList()) }
            return BookRepositoryImpl(
                bookDao = db.bookDao(),
                chapterDao = db.chapterDao(),
                audioFileDao = db.audioFileDao(),
                searchDao = db.searchDao(),
                collectionBookDao = db.collectionBookDao(),
                transactionRunner = RoomTransactionRunner(db),
                imageStorage = mock<ImageStorage> { every { exists(any()) } returns false },
                joinSources = BookDetailJoinSources(genreRepository, tagRepository, moodRepository),
                networkMonitor = mock<NetworkMonitor> { every { isOnline() } returns false },
                channel = RpcChannel.forTest(mock<BookService>()),
                bookSyncDomainHandler = mock<SyncDomainHandler<BookSyncPayload>>(),
            )
        }

        suspend fun seedVisibleAndHeld(db: ListenUpDatabase) {
            HeldBookFixture.seedBook(db, "visible", title = "Mistborn")
            HeldBookFixture.seedBook(db, "held", title = "Mistwraith")
            HeldBookFixture.publish(db, "visible")
            HeldBookFixture.hold(db, "held")
        }

        test("the library list leaves a held book out") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
                repository(db).observeBookListItems().test {
                    awaitItem().map { it.id.value } shouldBe listOf("visible")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the id-addressed list leaves a held book out, and takes it back on release") {
            withHeldBookDb { db ->
                seedVisibleAndHeld(db)
                repository(db).observeBookListItems(listOf("visible", "held")).test {
                    awaitItem().map { it.id.value } shouldBe listOf("visible")

                    HeldBookFixture.applyReleaseEcho(db, "held")

                    awaitItemMatching { items -> items.size == 2 }.map { it.id.value }.toSet() shouldBe
                        setOf("visible", "held")
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("book search (the App Intents play path) leaves held books out before its limit") {
            withHeldBookDb { db ->
                // A full page of held books that each outrank the one visible book: filtered after the
                // limit, the page would come back empty and the visible book would never be offered.
                val heldIds = (1..BookRepositoryImpl.SEARCH_LIMIT).map { "held-$it" }
                heldIds.forEach { id ->
                    HeldBookFixture.seedBook(db, id, title = "Mist")
                    HeldBookFixture.hold(db, id)
                    db.searchDao().insertBookFts(id, "Mist", null, null, null, null, null, null)
                }
                HeldBookFixture.seedBook(db, "visible", title = "Mist over the long and winding road")
                HeldBookFixture.publish(db, "visible")
                db.searchDao().insertBookFts("visible", "Mist over the long and winding road", null, null, null, null, null, null)

                // The premise: the held books fill the whole page of the unfiltered search.
                db
                    .searchDao()
                    .searchBooks("mist*", limit = BookRepositoryImpl.SEARCH_LIMIT)
                    .map { it.book.id.value }
                    .toSet() shouldBe
                    heldIds.toSet()

                repository(db).search("mist").test {
                    awaitItem().map { it.id.value } shouldBe listOf("visible")
                    awaitComplete()
                }
            }
        }
    })
