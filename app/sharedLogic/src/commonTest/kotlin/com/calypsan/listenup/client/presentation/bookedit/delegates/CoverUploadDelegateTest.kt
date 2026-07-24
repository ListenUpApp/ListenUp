package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.domain.repository.ImageStagingRepository
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify
import dev.mokkery.verify.VerifyMode
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CoverUploadDelegateTest :
    FunSpec({

        fun buildDelegate(
            state: MutableStateFlow<BookEditUiState>,
            imageStagingRepository: ImageStagingRepository,
        ): CoverUploadDelegate {
            val scope: CoroutineScope = TestScope(StandardTestDispatcher())
            return CoverUploadDelegate(
                state = state,
                imageStagingRepository = imageStagingRepository,
                scope = scope,
                errorBus = ErrorBus(),
                onChangesMade = {},
            )
        }

        fun defaultUiState(
            bookId: String,
            stagingCoverPath: String?,
        ): BookEditUiState =
            BookEditUiState(
                bookId = bookId,
                stagingCoverPath = stagingCoverPath,
            )

        test("cleanupStagingOnClear does nothing when bookId is blank") {
            runTest {
                val state = MutableStateFlow(defaultUiState(bookId = "", stagingCoverPath = "/tmp/staging"))
                val repo: ImageStagingRepository = mock()
                val delegate = buildDelegate(state, repo)

                delegate.cleanupStagingOnClear()

                verify(mode = VerifyMode.not) {
                    repo.requestBookCoverStagingCleanup(any())
                }
            }
        }

        test("cleanupStagingOnClear does nothing when no staging cover path") {
            runTest {
                val state = MutableStateFlow(defaultUiState(bookId = "book-1", stagingCoverPath = null))
                val repo: ImageStagingRepository = mock()
                val delegate = buildDelegate(state, repo)

                delegate.cleanupStagingOnClear()

                verify(mode = VerifyMode.not) {
                    repo.requestBookCoverStagingCleanup(any())
                }
            }
        }

        test("cleanupStagingOnClear requests cleanup when bookId and staging path are present") {
            runTest {
                val state = MutableStateFlow(defaultUiState(bookId = "book-1", stagingCoverPath = "/tmp/staging-book-1.jpg"))
                val repo: ImageStagingRepository = mock()
                every { repo.requestBookCoverStagingCleanup(any()) } returns Unit
                val delegate = buildDelegate(state, repo)

                delegate.cleanupStagingOnClear()

                verify { repo.requestBookCoverStagingCleanup(BookId("book-1")) }
            }
        }

        test("cleanupStagingOnCancel requests cleanup when bookId and staging path are present") {
            runTest {
                val state = MutableStateFlow(defaultUiState(bookId = "book-1", stagingCoverPath = "/tmp/staging-book-1.jpg"))
                val repo: ImageStagingRepository = mock()
                every { repo.requestBookCoverStagingCleanup(any()) } returns Unit
                val delegate = buildDelegate(state, repo)

                delegate.cleanupStagingOnCancel()

                verify { repo.requestBookCoverStagingCleanup(BookId("book-1")) }
            }
        }
    })
