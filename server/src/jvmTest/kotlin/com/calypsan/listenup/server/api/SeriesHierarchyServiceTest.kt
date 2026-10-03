@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SeriesError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SeriesSyncPayload
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class SeriesHierarchyServiceTest :
    FunSpec({

        // ── createSeries ───────────────────────────────────────────────────────

        test("createSeries makes a root series with no books") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val created =
                        deps.service
                            .createSeries("Cosmere", parentId = null)
                            .shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload>>()
                            .data

                    created.name shouldBe "Cosmere"
                    created.parentId shouldBe null
                    deps.series(SeriesId(created.id)).deletedAt shouldBe null
                }
            }
        }

        test("createSeries under a parent appends it after the existing sub-series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)

                    val created =
                        deps.service
                            .createSeries("Stormlight Archive", parentId = cosmere)
                            .shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload>>()
                            .data

                    created.parentId shouldBe cosmere.value
                    created.parentPosition shouldBe 1
                }
            }
        }

        test("createSeries refuses a blank name, a taken name and a missing parent") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    deps.seriesRepo.resolveOrCreate("Mistborn")

                    deps.service.createSeries("   ", null).shouldFailWith<SeriesError.InvalidInput>()
                    deps.service.createSeries("mistborn", null).shouldFailWith<SeriesError.NameAlreadyExists>()
                    deps.service.createSeries("Cosmere", SeriesId("missing")).shouldFailWith<SeriesError.ParentNotFound>()
                    deps.seriesRepo.liveIdForName("Cosmere") shouldBe null
                }
            }
        }

        test("createSeries with no parent revives a deleted series as a root") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn =
                        deps.service
                            .createSeries("Mistborn", parentId = cosmere)
                            .shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload>>()
                            .data
                    deps.service.deleteSeries(SeriesId(mistborn.id)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val recreated =
                        deps.service
                            .createSeries("Mistborn", parentId = null)
                            .shouldBeInstanceOf<AppResult.Success<SeriesSyncPayload>>()
                            .data

                    recreated.deletedAt shouldBe null
                    recreated.parentId shouldBe null
                    recreated.parentPosition shouldBe null
                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly emptyList()
                }
            }
        }

        test("createSeries refuses a name that was merged into another series, and leaves that series alone") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val elsewhere = deps.seriesRepo.resolveOrCreate("Elsewhere")
                    val duplicate = deps.seriesRepo.resolveOrCreate("Mistborn Saga")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)
                    deps.service.mergeSeries(source = duplicate, target = mistborn).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    val before = deps.series(mistborn)

                    deps.service.createSeries("Mistborn Saga", elsewhere).shouldFailWith<SeriesError.NameAlreadyExists>()
                    deps.service.createSeries("Mistborn Saga", null).shouldFailWith<SeriesError.NameAlreadyExists>()

                    deps.series(mistborn).parentId shouldBe cosmere.value
                    deps.series(mistborn).parentPosition shouldBe 0
                    deps.series(mistborn).revision shouldBe before.revision
                }
            }
        }

        // ── setSeriesParent ────────────────────────────────────────────────────

        test("setSeriesParent appends the series to its new parent, and null makes it a root again") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val stormlight = deps.seriesRepo.resolveOrCreate("Stormlight Archive")

                    deps.service.setSeriesParent(mistborn, cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()
                    deps.service.setSeriesParent(stormlight, cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly
                        listOf(mistborn.value, stormlight.value)

                    deps.service.setSeriesParent(mistborn, null).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(mistborn).parentId shouldBe null
                    deps.series(mistborn).parentPosition shouldBe null
                }
            }
        }

        test("setSeriesParent to the parent it already has changes nothing") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    deps.place(mistborn, parent = cosmere, position = 0)
                    val before = deps.series(mistborn).revision

                    deps.service.setSeriesParent(mistborn, cosmere).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.series(mistborn).revision shouldBe before
                }
            }
        }

        test("setSeriesParent refuses a cycle at depth one and depth three") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val era1 = deps.seriesRepo.resolveOrCreate("Mistborn Era 1")
                    deps.place(mistborn, parent = cosmere, position = 0)
                    deps.place(era1, parent = mistborn, position = 0)

                    deps.service.setSeriesParent(cosmere, cosmere).shouldFailWith<SeriesError.HierarchyCycle>()
                    deps.service.setSeriesParent(cosmere, era1).shouldFailWith<SeriesError.HierarchyCycle>()
                    deps.series(cosmere).parentId shouldBe null
                }
            }
        }

        test("setSeriesParent reports a missing series and a missing parent distinctly") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val mistborn = deps.seriesRepo.resolveOrCreate("Mistborn")

                    deps.service.setSeriesParent(SeriesId("missing"), mistborn).shouldFailWith<SeriesError.NotFound>()
                    deps.service.setSeriesParent(mistborn, SeriesId("missing")).shouldFailWith<SeriesError.ParentNotFound>()
                }
            }
        }

        // ── reorderChildSeries ─────────────────────────────────────────────────

        test("reorderChildSeries rewrites sibling positions to the given order") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val a = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val b = deps.seriesRepo.resolveOrCreate("Stormlight Archive")
                    val c = deps.seriesRepo.resolveOrCreate("Warbreaker")
                    listOf(a, b, c).forEachIndexed { index, id -> deps.place(id, parent = cosmere, position = index) }

                    deps.service.reorderChildSeries(cosmere, listOf(c, a, b)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                    deps.seriesRepo.liveTree().childrenOf(cosmere.value) shouldContainExactly
                        listOf(c.value, a.value, b.value)
                }
            }
        }

        test("reorderChildSeries refuses anything but a permutation of the live sub-series") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val a = deps.seriesRepo.resolveOrCreate("Mistborn")
                    val b = deps.seriesRepo.resolveOrCreate("Stormlight Archive")
                    val stranger = deps.seriesRepo.resolveOrCreate("Narnia")
                    deps.place(a, parent = cosmere, position = 0)
                    deps.place(b, parent = cosmere, position = 1)

                    deps.service.reorderChildSeries(cosmere, listOf(a)).shouldFailWith<SeriesError.InvalidInput>()
                    deps.service.reorderChildSeries(cosmere, listOf(a, stranger)).shouldFailWith<SeriesError.InvalidInput>()
                    deps.service.reorderChildSeries(cosmere, listOf(a, a)).shouldFailWith<SeriesError.InvalidInput>()
                }
            }
        }

        // ── permission ─────────────────────────────────────────────────────────

        test("every hierarchy write is denied without a principal") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val deps = makeHierarchyDeps(this)
                runTest {
                    val cosmere = deps.seriesRepo.resolveOrCreate("Cosmere")
                    val unscoped = deps.service.copyWith(PrincipalProvider.None)

                    unscoped.createSeries("New", null).shouldFailWith<AuthError.PermissionDenied>()
                    unscoped.setSeriesParent(cosmere, null).shouldFailWith<AuthError.PermissionDenied>()
                    unscoped.reorderChildSeries(cosmere, emptyList()).shouldFailWith<AuthError.PermissionDenied>()
                }
            }
        }
    })

/** Asserts the result is a failure carrying an [E]. */
private inline fun <reified E : Any> AppResult<*>.shouldFailWith(): E =
    shouldBeInstanceOf<AppResult.Failure>().error.shouldBeInstanceOf<E>()
