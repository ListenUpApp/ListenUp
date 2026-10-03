@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

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
    })
