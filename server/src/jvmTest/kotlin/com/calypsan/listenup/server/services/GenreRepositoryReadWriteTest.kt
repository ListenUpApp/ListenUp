package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.GenreSyncPayload
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class GenreRepositoryReadWriteTest :
    FunSpec({

        test("should round-trip a root genre through upsert + findById") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-fiction", name = "Fiction", slug = "fiction", path = "/fiction"))

                    val readBack = repo.findById("g-fiction")

                    readBack.shouldNotBeNull()
                    readBack.name shouldBe "Fiction"
                    readBack.slug shouldBe "fiction"
                    readBack.path shouldBe "/fiction"
                    readBack.parentId.shouldBeNull()
                    readBack.depth shouldBe 0
                }
            }
        }

        test("should round-trip a nested genre with parentId, depth, and 2-segment path") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-fiction", name = "Fiction", slug = "fiction", path = "/fiction"))
                    repo.upsert(
                        genrePayloadFixture(
                            id = "g-fantasy",
                            name = "Fantasy",
                            slug = "fantasy",
                            path = "/fiction/fantasy",
                            parentId = "g-fiction",
                            depth = 1,
                        ),
                    )

                    val readBack = repo.findById("g-fantasy")

                    readBack.shouldNotBeNull()
                    readBack.parentId shouldBe "g-fiction"
                    readBack.depth shouldBe 1
                    readBack.path shouldBe "/fiction/fantasy"
                }
            }
        }

        test("findBySlug returns null for an unknown slug") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.findBySlug("ghost-slug").shouldBeNull()
                }
            }
        }

        test("findBySlug returns the genre for an existing slug") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-fiction", name = "Fiction", slug = "fiction", path = "/fiction"))

                    val readBack = repo.findBySlug("fiction")

                    readBack.shouldNotBeNull()
                    readBack.id shouldBe "g-fiction"
                    readBack.name shouldBe "Fiction"
                }
            }
        }

        test("findByPath returns the genre for an existing path") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-fiction", name = "Fiction", slug = "fiction", path = "/fiction"))
                    repo.upsert(
                        genrePayloadFixture(
                            id = "g-fantasy",
                            name = "Fantasy",
                            slug = "fantasy",
                            path = "/fiction/fantasy",
                            parentId = "g-fiction",
                            depth = 1,
                        ),
                    )

                    val readBack = repo.findByPath("/fiction/fantasy")

                    readBack.shouldNotBeNull()
                    readBack.id shouldBe "g-fantasy"
                }
            }
        }

        test("count returns 0 on an empty database") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.count() shouldBe 0L
                }
            }
        }

        test("count returns N after N upserts") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-a", name = "A", slug = "a", path = "/a"))
                    repo.upsert(genrePayloadFixture(id = "g-b", name = "B", slug = "b", path = "/b"))
                    repo.upsert(genrePayloadFixture(id = "g-c", name = "C", slug = "c", path = "/c"))

                    repo.count() shouldBe 3L
                }
            }
        }

        test("tombstoned genre: readPayload returns payload with deletedAt set; findBySlug excludes it") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(genrePayloadFixture(id = "g-fiction", name = "Fiction", slug = "fiction", path = "/fiction"))
                    repo.softDelete(GenreId("g-fiction"))

                    val readBack = repo.findById("g-fiction")
                    readBack.shouldNotBeNull()
                    readBack.deletedAt.shouldNotBeNull()

                    repo.findBySlug("fiction").shouldBeNull()
                }
            }
        }
    })

private fun genrePayloadFixture(
    id: String,
    name: String,
    slug: String,
    path: String,
    parentId: String? = null,
    depth: Int = 0,
    sortOrder: Int = 0,
    color: String? = null,
    description: String? = null,
): GenreSyncPayload =
    GenreSyncPayload(
        id = id,
        name = name,
        slug = slug,
        path = path,
        parentId = parentId,
        depth = depth,
        sortOrder = sortOrder,
        color = color,
        description = description,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
