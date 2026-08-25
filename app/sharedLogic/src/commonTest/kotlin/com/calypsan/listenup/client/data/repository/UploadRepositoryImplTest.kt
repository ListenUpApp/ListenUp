package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.uploads.UploadFinalizeResult
import com.calypsan.listenup.api.dto.uploads.UploadSessionSummary
import com.calypsan.listenup.api.dto.uploads.UploadedBook
import com.calypsan.listenup.api.dto.uploads.UploadedBookStatus
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.error.UploadError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.fileSourceOf
import com.calypsan.listenup.client.data.remote.UploadApiContract
import com.calypsan.listenup.client.domain.repository.UploadCandidate
import com.calypsan.listenup.client.domain.repository.UploadStep
import com.calypsan.listenup.core.FileSource
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * [UploadRepositoryImpl] — the upload session state machine.
 *
 * The thing worth pinning here is not that a happy-path upload works; it is that **no exit path
 * leaves a staging directory stranded on the server**. A session that is neither finalized nor
 * abandoned holds half-uploaded audio that nothing later cleans up, so every failure test below
 * asserts the abandon actually happened — and the happy-path test asserts it did *not*, because a
 * finalize already removed the staging directory and a second delete would be noise.
 */
class UploadRepositoryImplTest :
    FunSpec({

        fun candidate(
            relPath: String,
            bytes: Int,
        ): UploadCandidate =
            UploadCandidate(
                relPath = relPath,
                source = fileSourceOf(ByteArray(bytes), relPath.substringAfterLast('/')),
            )

        /** Records every call so a test can assert on ordering and on cleanup, not just returns. */
        class RecordingApi(
            private val createResult: AppResult<UploadSessionSummary> =
                AppResult.Success(UploadSessionSummary("s1", 0, 0)),
            private val uploadResults: Map<String, AppResult<UploadSessionSummary>> = emptyMap(),
            private val finalizeResult: AppResult<UploadFinalizeResult> =
                AppResult.Success(UploadFinalizeResult(books = emptyList())),
            private val progressChunks: List<Long> = emptyList(),
        ) : UploadApiContract {
            val uploadedRelPaths = mutableListOf<String>()
            var abandonCount = 0
            var finalizeCount = 0

            override suspend fun createSession() = createResult

            override suspend fun uploadFile(
                sessionId: String,
                relPath: String,
                source: FileSource,
                onProgress: suspend (Long, Long?) -> Unit,
            ): AppResult<UploadSessionSummary> {
                uploadedRelPaths += relPath
                progressChunks.forEach { onProgress(it, source.size) }
                return uploadResults[relPath]
                    ?: AppResult.Success(UploadSessionSummary(sessionId, uploadedRelPaths.size, 0))
            }

            override suspend fun finalize(sessionId: String): AppResult<UploadFinalizeResult> {
                finalizeCount++
                return finalizeResult
            }

            override suspend fun abandon(sessionId: String): AppResult<Unit> {
                abandonCount++
                return AppResult.Success(Unit)
            }
        }

        test("CONTROL — stages every file in order, finalizes once, and abandons nothing") {
            runTest {
                val api =
                    RecordingApi(
                        finalizeResult =
                            AppResult.Success(
                                UploadFinalizeResult(
                                    books =
                                        listOf(
                                            UploadedBook(
                                                title = "The Land: Founding",
                                                status = UploadedBookStatus.IMPORTED,
                                                rootRelPath = "Aleron Kong/The Land Founding",
                                            ),
                                        ),
                                ),
                            ),
                    )

                val steps =
                    UploadRepositoryImpl(api)
                        .upload(listOf(candidate("01.m4b", 10), candidate("02.m4b", 10)))
                        .toList()

                api.uploadedRelPaths shouldContainExactly listOf("01.m4b", "02.m4b")
                api.finalizeCount shouldBe 1
                withClue("finalize already removed staging — abandoning again would be noise") {
                    api.abandonCount shouldBe 0
                }
                steps.last().shouldBeInstanceOf<UploadStep.Done>()
                (steps.last() as UploadStep.Done)
                    .result.books
                    .single()
                    .title shouldBe "The Land: Founding"
            }
        }

        test("preserves the relative paths the user's selection implied") {
            runTest {
                val api = RecordingApi()

                UploadRepositoryImpl(api)
                    .upload(
                        listOf(
                            candidate("Chaos Seeds/Book 1/01.m4b", 4),
                            candidate("Chaos Seeds/Book 1/cover.jpg", 4),
                        ),
                    ).toList()

                withClue("the client transmits structure faithfully; grouping is the server's call") {
                    api.uploadedRelPaths shouldContainExactly
                        listOf("Chaos Seeds/Book 1/01.m4b", "Chaos Seeds/Book 1/cover.jpg")
                }
            }
        }

        test("abandons the session when a file transfer fails, and stops sending the rest") {
            runTest {
                val api =
                    RecordingApi(
                        uploadResults =
                            mapOf("02.m4b" to AppResult.Failure(UploadError.FileTransferFailed())),
                    )

                val steps =
                    UploadRepositoryImpl(api)
                        .upload(
                            listOf(candidate("01.m4b", 10), candidate("02.m4b", 10), candidate("03.m4b", 10)),
                        ).toList()

                withClue("a dead transfer must not keep pushing the remaining files at it") {
                    api.uploadedRelPaths shouldContainExactly listOf("01.m4b", "02.m4b")
                }
                api.finalizeCount shouldBe 0
                withClue("the staged bytes are ours to clean up — nothing else will") {
                    api.abandonCount shouldBe 1
                }
                steps.last().shouldBeInstanceOf<UploadStep.Failed>()
            }
        }

        test("abandons the session when finalize fails") {
            runTest {
                val api = RecordingApi(finalizeResult = AppResult.Failure(UploadError.SessionNotFound()))

                val steps = UploadRepositoryImpl(api).upload(listOf(candidate("01.m4b", 10))).toList()

                api.abandonCount shouldBe 1
                steps.last().shouldBeInstanceOf<UploadStep.Failed>()
            }
        }

        test("mints no session at all when the create call fails") {
            runTest {
                val api = RecordingApi(createResult = AppResult.Failure(TransportError.NetworkUnavailable()))

                val steps = UploadRepositoryImpl(api).upload(listOf(candidate("01.m4b", 10))).toList()

                withClue("there is no session to abandon if one was never minted") {
                    api.abandonCount shouldBe 0
                }
                api.uploadedRelPaths.shouldContainExactly(emptyList())
                steps shouldContainExactly listOf(UploadStep.Failed(TransportError.NetworkUnavailable()))
            }
        }

        test("counts bytes across file boundaries rather than restarting at each file") {
            runTest {
                // Each file reports 5 then 10 bytes sent; two 10-byte files.
                val api = RecordingApi(progressChunks = listOf(5L, 10L))

                val staging =
                    UploadRepositoryImpl(api)
                        .upload(listOf(candidate("01.m4b", 10), candidate("02.m4b", 10)))
                        .toList()
                        .filterIsInstance<UploadStep.Staging>()

                withClue("a progress bar over bytesSent must never travel backwards") {
                    staging.map { it.bytesSent } shouldContainExactly listOf(0L, 5L, 10L, 10L, 15L, 20L)
                }
                staging.forEach { it.totalBytes shouldBe 20L }
            }
        }

        test("uploads nothing and mints no session for an empty selection") {
            runTest {
                val api = RecordingApi()

                val steps = UploadRepositoryImpl(api).upload(emptyList()).toList()

                api.uploadedRelPaths.shouldContainExactly(emptyList())
                api.abandonCount shouldBe 0
                withClue("zero files is zero books — true, and not a failure to report") {
                    steps shouldContainExactly
                        listOf(UploadStep.Done(UploadFinalizeResult(books = emptyList())))
                }
            }
        }
    })
