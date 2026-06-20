package com.calypsan.listenup.server.services

import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.asSqlDatabase
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

class GenreHierarchyFromLadderTest :
    FunSpec({

        test("nests each rung under the previous one, leaf is most specific") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                val hierarchy = GenreHierarchyFromLadder(this.asSqlDatabase(), repo, GenreAutoCreator(repo))
                runTest {
                    val ids = hierarchy.ensureLadder(listOf("Fiction", "Fantasy", "LitRPG"))

                    ids.size shouldBe 3
                    val leaf = repo.findById(ids.last())
                    leaf.shouldNotBeNull()
                    leaf.name shouldBe "LitRPG"
                    leaf.depth shouldBe 2
                    leaf.path shouldBe "/fiction/fantasy/litrpg"

                    val parent = repo.findById(leaf.parentId!!)
                    parent.shouldNotBeNull()
                    parent.name shouldBe "Fantasy"
                }
            }
        }

        test("does not move a genre the user already arranged under a parent") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                val autoCreator = GenreAutoCreator(repo)
                val hierarchy = GenreHierarchyFromLadder(this.asSqlDatabase(), repo, autoCreator)
                runTest {
                    // The user has arranged "Fantasy" under "Reference" (a deliberate, non-flat placement).
                    val referenceId = autoCreator.findOrCreateFlatGenreId("Reference")
                    val arrangedFantasyId =
                        java.util.UUID
                            .randomUUID()
                            .toString()
                    repo.upsert(
                        com.calypsan.listenup.api.sync.GenreSyncPayload(
                            id = arrangedFantasyId,
                            name = "Fantasy",
                            slug = "fantasy",
                            path = "/reference/fantasy",
                            parentId = referenceId,
                            depth = 1,
                            sortOrder = 0,
                        ),
                    )

                    hierarchy.ensureLadder(listOf("Fiction", "Fantasy", "LitRPG"))

                    // The arranged node keeps its manual parent — the ladder does not move it.
                    val fantasy = repo.findById(arrangedFantasyId)
                    fantasy.shouldNotBeNull()
                    fantasy.parentId shouldBe referenceId
                    fantasy.depth shouldBe 1
                    fantasy.path shouldBe "/reference/fantasy"
                }
            }
        }

        test("nests a pre-existing FLAT genre when a ladder arrives (scanner-flat becomes nested)") {
            withInMemoryDatabase {
                val repo = GenreRepository(db = this.asSqlDatabase(), bus = ChangeBus(), registry = SyncRegistry())
                val autoCreator = GenreAutoCreator(repo)
                val hierarchy = GenreHierarchyFromLadder(this.asSqlDatabase(), repo, autoCreator)
                runTest {
                    // "Fantasy" was created flat by an earlier scan (no parent).
                    val fantasyId = autoCreator.findOrCreateFlatGenreId("Fantasy")

                    hierarchy.ensureLadder(listOf("Fiction", "Fantasy", "LitRPG"))

                    // An incoming ladder nests the flat genre under its parent.
                    val fantasy = repo.findById(fantasyId)
                    fantasy.shouldNotBeNull()
                    fantasy.parentId.shouldNotBeNull()
                    repo.findById(fantasy.parentId!!)!!.name shouldBe "Fiction"
                    fantasy.depth shouldBe 1
                    fantasy.path shouldBe "/fiction/fantasy"
                }
            }
        }
    })
