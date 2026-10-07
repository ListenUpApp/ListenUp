@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.readingorder.ReadingOrderChoice
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.ReadingOrderId
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/** A series merge moves the source's reading orders onto the target, and undo hands them back (#962). */
class ReadingOrderSeriesMergeTest :
    FunSpec({
        test("merging moves the source's orders onto the target, renaming a clash; undo restores series and names") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("jess")
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val saga = deps.hierarchy.seriesRepo.resolveOrCreate("Mistborn Saga")
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("uro"), ids.mistborn, "URO")
                    simon.createReadingOrder(ReadingOrderId("era"), ids.mistborn, "Era One")
                    simon.createReadingOrder(ReadingOrderId("saga-era"), saga, "era one")
                    deps.serviceAs("jess").chooseReadingOrder(ids.mistborn, ReadingOrderChoice.UserMade(ReadingOrderId("uro")))

                    deps.hierarchy.service.mergeSeries(ids.mistborn, saga).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.orders.findLive("uro")!!.let {
                        it.seriesId shouldBe saga.value
                        it.name shouldBe "URO"
                    }
                    deps.orders.findLive("era")!!.let {
                        it.seriesId shouldBe saga.value
                        it.name shouldBe "Era One (from Mistborn)"
                    }
                    deps.orders.findLive("saga-era")!!.name shouldBe "era one"
                    // Moved orders are live and editable on the target.
                    simon.renameReadingOrder(ReadingOrderId("uro"), "URO+").shouldBeInstanceOf<AppResult.Success<Unit>>()
                    // The follow on the merged-away series is left where it is, dormant with its series.
                    deps.follows.findLive("jess:${ids.mistborn.value}")!!.readingOrderId shouldBe "uro"

                    val receipt =
                        deps.hierarchy.service
                            .listMergeReceipts(saga)
                            .shouldBeInstanceOf<AppResult.Success<List<com.calypsan.listenup.api.dto.MergeReceipt>>>()
                            .data
                            .single()
                    deps.hierarchy.service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<*>>()

                    deps.orders.findLive("uro")!!.let {
                        it.seriesId shouldBe ids.mistborn.value
                        it.name shouldBe "URO"
                    }
                    deps.orders.findLive("era")!!.let {
                        it.seriesId shouldBe ids.mistborn.value
                        it.name shouldBe "Era One"
                    }
                    deps.orders.findLive("saga-era")!!.seriesId shouldBe saga.value
                }
            }
        }

        test("an order deleted after the merge stays deleted through undo, and one moved off the target is left alone") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeReadingOrderDeps(this)
                runTest {
                    val ids = deps.seedCosmere()
                    val saga = deps.hierarchy.seriesRepo.resolveOrCreate("Mistborn Saga")
                    val simon = deps.serviceAs("simon", UserRole.ADMIN)
                    simon.createReadingOrder(ReadingOrderId("gone"), ids.mistborn, "Gone")
                    simon.createReadingOrder(ReadingOrderId("kept"), ids.mistborn, "Kept")
                    deps.hierarchy.service.mergeSeries(ids.mistborn, saga).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    simon.deleteReadingOrder(ReadingOrderId("gone")).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val receipt =
                        deps.hierarchy.service
                            .listMergeReceipts(saga)
                            .shouldBeInstanceOf<AppResult.Success<List<com.calypsan.listenup.api.dto.MergeReceipt>>>()
                            .data
                            .single()
                    deps.hierarchy.service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<*>>()

                    deps.orders.findLive("gone") shouldBe null
                    deps.orders.findLive("kept")!!.seriesId shouldBe ids.mistborn.value
                }
            }
        }
    })
