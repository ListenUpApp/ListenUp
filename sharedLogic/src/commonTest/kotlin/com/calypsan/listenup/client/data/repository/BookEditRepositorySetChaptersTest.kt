package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.BookRpcFactory
import com.calypsan.listenup.client.data.remote.CollectionRpcFactory
import com.calypsan.listenup.core.BookId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

class BookEditRepositorySetChaptersTest :
    FunSpec({
        test("setBookChapters dispatches to BookService over RPC") {
            runTest {
                val service = mock<BookService>(MockMode.autoUnit)
                val rpcFactory = mock<BookRpcFactory>(MockMode.autoUnit)
                everySuspend { rpcFactory.bookService() } returns service
                val chapters = listOf(ChapterInput(id = "c1", title = "A", startTime = 0, duration = 1000))
                everySuspend { service.setBookChapters(BookId("b1"), chapters) } returns AppResult.Success(Unit)

                val repo = BookEditRepositoryImpl(rpcFactory, mock<CollectionRpcFactory>(MockMode.autoUnit))
                repo.setBookChapters(BookId("b1"), chapters)

                verifySuspend { service.setBookChapters(BookId("b1"), chapters) }
            }
        }
    })
