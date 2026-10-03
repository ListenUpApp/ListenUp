package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.fake.noopOfflineEditor
import com.calypsan.listenup.core.SeriesId
import dev.mokkery.MockMode
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * [SeriesEditRepositoryImpl]'s hierarchy surface (#962). Like a merge, these are server-canonical:
 * the server owns the cycle check and the sibling position, and the result reaches Room through
 * the firehose.
 */
class SeriesEditRepositoryHierarchyTest :
    FunSpec({
        fun repo(service: SeriesService): SeriesEditRepositoryImpl =
            SeriesEditRepositoryImpl(
                channel = RpcChannel.forTest(service),
                seriesDao = mock(MockMode.autofill),
                offlineEditor = noopOfflineEditor(),
            )

        test("creating a series returns the new series' id") {
            runTest {
                val service = mock<SeriesService>()
                val created =
                    SeriesSyncPayload(
                        id = "new",
                        name = "Cosmere",
                        sortName = null,
                        revision = 1,
                        updatedAt = 1,
                        createdAt = 1,
                        deletedAt = null,
                    )
                everySuspend { service.createSeries("Cosmere", null) } returns AppResult.Success(created)

                repo(service).createSeries("Cosmere", parentId = null) shouldBe AppResult.Success(SeriesId("new"))
            }
        }

        test("setting a parent goes to the server") {
            runTest {
                val service = mock<SeriesService>()
                everySuspend { service.setSeriesParent(SeriesId("mistborn"), SeriesId("cosmere")) } returns
                    AppResult.Success(Unit)

                repo(service).setParent(SeriesId("mistborn"), SeriesId("cosmere")) shouldBe AppResult.Success(Unit)
            }
        }

        test("a refused re-parent arrives as its typed error, untouched") {
            runTest {
                val service = mock<SeriesService>()
                everySuspend { service.setSeriesParent(SeriesId("cosmere"), SeriesId("mistborn")) } returns
                    AppResult.Failure(SeriesError.HierarchyCycle())

                repo(service)
                    .setParent(SeriesId("cosmere"), SeriesId("mistborn"))
                    .shouldBeInstanceOf<AppResult.Failure>()
                    .error
                    .shouldBeInstanceOf<SeriesError.HierarchyCycle>()
            }
        }

        test("reordering sub-series goes to the server") {
            runTest {
                val service = mock<SeriesService>()
                val order = listOf(SeriesId("stormlight"), SeriesId("mistborn"))
                everySuspend { service.reorderChildSeries(SeriesId("cosmere"), order) } returns AppResult.Success(Unit)

                repo(service).reorderChildren(SeriesId("cosmere"), order) shouldBe AppResult.Success(Unit)
            }
        }
    })
