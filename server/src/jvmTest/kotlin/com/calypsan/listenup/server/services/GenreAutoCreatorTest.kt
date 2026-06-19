package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class GenreAutoCreatorTest :
    FunSpec({

        test("creates a flat live genre for an unknown name and returns its id") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val autoCreator = GenreAutoCreator(repo)
                runTest {
                    val id = autoCreator.findOrCreateFlatGenreId("Progression Fantasy")

                    val created = repo.findById(id)
                    created.shouldNotBeNull()
                    created.name shouldBe "Progression Fantasy"
                    created.slug shouldBe "progression-fantasy"
                    created.path shouldBe "/progression-fantasy"
                    created.depth shouldBe 0
                    created.parentId.shouldBeNull()
                }
            }
        }

        test("reuses the existing genre case-insensitively rather than duplicating") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val autoCreator = GenreAutoCreator(repo)
                runTest {
                    val firstId = autoCreator.findOrCreateFlatGenreId("LitRPG")
                    val secondId = autoCreator.findOrCreateFlatGenreId("litrpg")

                    secondId shouldBe firstId
                    repo.count() shouldBe 1L
                }
            }
        }
    })
