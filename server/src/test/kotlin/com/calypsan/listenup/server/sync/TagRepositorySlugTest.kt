package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class TagRepositorySlugTest :
    FunSpec({

        test("upsert preserves slug on read-back") {
            withInMemoryDatabase {
                val repo = TagRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result = repo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    result.shouldBeInstanceOf<AppResult.Success<Tag>>()
                    val saved = (result as AppResult.Success).data
                    saved.slug shouldBe "sci-fi"
                }
            }
        }

        test("pullSince includes slug in returned payload") {
            withInMemoryDatabase {
                val repo = TagRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "t1", name = "Fantasy", slug = "fantasy", revision = 0, updatedAt = 0))
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 100)
                    page.items.first().slug shouldBe "fantasy"
                }
            }
        }

        test("upsert update preserves existing slug") {
            withInMemoryDatabase {
                val repo = TagRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    val updated = repo.upsert(Tag(id = "t1", name = "Science Fiction", slug = "sci-fi", revision = 0, updatedAt = 0))
                    updated.shouldBeInstanceOf<AppResult.Success<Tag>>()
                    val saved = (updated as AppResult.Success).data
                    saved.name shouldBe "Science Fiction"
                    saved.slug shouldBe "sci-fi"
                }
            }
        }

        test("unique slug constraint prevents duplicate live slugs") {
            withInMemoryDatabase {
                val repo = TagRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(Tag(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 0, updatedAt = 0))
                    // Inserting a different id with the same slug violates the partial unique index
                    val ex =
                        runCatching {
                            repo.upsert(Tag(id = "t2", name = "Science Fiction", slug = "sci-fi", revision = 0, updatedAt = 0))
                        }
                    ex.isFailure shouldBe true
                }
            }
        }
    })
