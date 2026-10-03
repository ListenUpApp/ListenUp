@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.MergeReceipt
import com.calypsan.listenup.api.dto.MergeUndoResult
import com.calypsan.listenup.api.dto.SeriesUpdate
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * How the series hierarchy survives the rest of a series' life: plain edits, rescans, deletes,
 * merges and merge undo.
 */
class SeriesHierarchyLifecycleTest :
    FunSpec({

        test("parent and position are stored and read back") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")

                    deps.place(mistborn, parent = cosmere, position = 0)

                    deps.series(mistborn).parentId shouldBe cosmere.value
                    deps.series(mistborn).parentPosition shouldBe 0
                    deps.series(cosmere).parentId shouldBe null
                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly listOf(mistborn.value)
                }
            }
        }

        test("a metadata edit keeps the parent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)

                    deps.service
                        .updateSeries(mistborn, SeriesUpdate(description = "Ash falls from the sky."))
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(mistborn).parentId shouldBe cosmere.value
                }
            }
        }

        test("a rescan that resolves the same names keeps the parent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)

                    deps.seriesRepo.resolveOrCreateAll(listOf("Mistborn", "Cosmere", "mistborn"))
                    deps.bookRepo.upsert(bookInSeries("final-empire", mistborn)).shouldBeInstanceOf<AppResult.Success<*>>()

                    deps.series(mistborn).parentId shouldBe cosmere.value
                    deps.series(mistborn).parentPosition shouldBe 0
                }
            }
        }

        test("deleting a parent lifts its sub-series to the grandparent, after the existing ones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val stormlight = deps.seriesRepo.resolveOrCreate("Stormlight Archive")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val era1 = deps.seriesRepo.resolveOrCreate("Mistborn Era 1")
                    val era2 = deps.seriesRepo.resolveOrCreate("Mistborn Era 2")
                    deps.place(stormlight, parent = cosmere, position = 0)
                    deps.place(mistborn, parent = cosmere, position = 1)
                    deps.place(era1, parent = mistborn, position = 0)
                    deps.place(era2, parent = mistborn, position = 1)

                    deps.service.deleteSeries(mistborn).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly
                        listOf(stormlight.value, era1.value, era2.value)
                }
            }
        }

        test("deleting a root parent leaves its sub-series as roots") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)

                    deps.service.deleteSeries(cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(mistborn).deletedAt shouldBe null
                    deps.series(mistborn).parentId shouldBe null
                    deps.series(mistborn).parentPosition shouldBe null
                }
            }
        }

        test("merging a parent moves its sub-series under the survivor, and undo hands them back in place") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val duplicate = deps.seriesRepo.resolveOrCreate("The Cosmere")
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val stormlight = deps.seriesRepo.resolveOrCreate("Stormlight Archive")
                    val elantris = deps.seriesRepo.resolveOrCreate("Elantris")
                    deps.place(elantris, parent = cosmere, position = 0)
                    deps.place(mistborn, parent = duplicate, position = 0)
                    deps.place(stormlight, parent = duplicate, position = 1)

                    deps.service.mergeSeries(source = duplicate, target = cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly
                        listOf(elantris.value, mistborn.value, stormlight.value)

                    val receipt =
                        deps.service
                            .listMergeReceipts(cosmere)
                            .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                            .data
                            .single()
                    deps.service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()

                    val tree = deps.seriesRepo.liveTree()
                    tree.childrenOf(duplicate.value) shouldContainExactly listOf(mistborn.value, stormlight.value)
                    tree.childrenOf(cosmere.value) shouldContainExactly listOf(elantris.value)
                }
            }
        }

        test("undo leaves alone a sub-series that was moved again after the merge") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val duplicate = deps.seriesRepo.resolveOrCreate("The Cosmere")
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = duplicate, position = 0)
                    deps.service.mergeSeries(source = duplicate, target = cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.service.setSeriesParent(mistborn, null).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val receipt =
                        deps.service
                            .listMergeReceipts(cosmere)
                            .shouldBeInstanceOf<AppResult.Success<List<MergeReceipt>>>()
                            .data
                            .single()
                    deps.service.undoSeriesMerge(receipt.id).shouldBeInstanceOf<AppResult.Success<MergeUndoResult>>()

                    deps.series(mistborn).parentId shouldBe null
                }
            }
        }

        test("merging a parent into its own sub-series lifts that sub-series instead of looping") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val universe = deps.seriesRepo.resolveOrCreate("Sanderson")
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val stormlight = deps.seriesRepo.resolveOrCreate("Stormlight Archive")
                    deps.place(cosmere, parent = universe, position = 0)
                    deps.place(mistborn, parent = cosmere, position = 0)
                    deps.place(stormlight, parent = cosmere, position = 1)

                    deps.service.mergeSeries(source = cosmere, target = mistborn).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val tree = deps.seriesRepo.liveTree()
                    deps.series(mistborn).parentId shouldBe universe.value
                    tree.childrenOf(mistborn.value) shouldContainExactly listOf(stormlight.value)
                    tree.ancestorsOf(stormlight.value) shouldContainExactly listOf(universe.value, mistborn.value)
                }
            }
        }

        test("deleting a series whose own parent is already gone leaves its sub-series as roots") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val gone = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val era1 = deps.seriesRepo.resolveOrCreate("Mistborn Era 1")
                    deps.place(mistborn, parent = gone, position = 0)
                    deps.place(era1, parent = mistborn, position = 0)
                    // Tombstone the parent underneath the service, so mistborn keeps a stale parent.
                    deps.seriesRepo.softDelete(gone).shouldBeInstanceOf<AppResult.Success<*>>()

                    deps.service.deleteSeries(mistborn).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(era1).deletedAt shouldBe null
                    deps.series(era1).parentId shouldBe null
                    deps.series(era1).parentPosition shouldBe null
                }
            }
        }

        test("merging into a sub-series when the source's own parent is already gone makes the survivor a root") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val gone = deps.seriesRepo.resolveOrCreate("Sanderson")
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(cosmere, parent = gone, position = 0)
                    deps.place(mistborn, parent = cosmere, position = 0)
                    deps.seriesRepo.softDelete(gone).shouldBeInstanceOf<AppResult.Success<*>>()

                    deps.service.mergeSeries(source = cosmere, target = mistborn).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(mistborn).deletedAt shouldBe null
                    deps.series(mistborn).parentId shouldBe null
                    deps.series(mistborn).parentPosition shouldBe null
                }
            }
        }
    })
