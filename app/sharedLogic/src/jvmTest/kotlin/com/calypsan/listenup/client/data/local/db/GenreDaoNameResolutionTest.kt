package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies [GenreDao.getIdsByNames] against a real in-memory [ListenUpDatabase].
 *
 * Covers the resolution primitive sync domain handlers use to map
 * server-sent genre names to local genre IDs. Case-insensitive via `COLLATE NOCASE`;
 * unknown names simply don't appear in the result set.
 */
class GenreDaoNameResolutionTest :
    FunSpec({
        suspend fun seedGenre(
            genreDao: GenreDao,
            id: String,
            name: String,
            slug: String = id,
        ) {
            genreDao.upsertAll(
                listOf(
                    GenreEntity(
                        id = id,
                        name = name,
                        slug = slug,
                        path = "/$slug",
                        parentId = null,
                        depth = 0,
                        sortOrder = 0,
                    ),
                ),
            )
        }

        test("getIdsByNames returns matching id-name pairs") {
            val db = createInMemoryTestDatabase()
            val genreDao = db.genreDao()
            try {
                runTest {
                    seedGenre(genreDao, id = "g1", name = "Fantasy")
                    seedGenre(genreDao, id = "g2", name = "Science Fiction")
                    seedGenre(genreDao, id = "g3", name = "Horror")

                    val result = genreDao.getIdsByNames(listOf("Fantasy", "Horror"))

                    result.size shouldBe 2
                    result.map { it.id }.toSet() shouldBe setOf("g1", "g3")
                }
            } finally {
                db.close()
            }
        }

        test("getIdsByNames matches case-insensitively") {
            val db = createInMemoryTestDatabase()
            val genreDao = db.genreDao()
            try {
                runTest {
                    seedGenre(genreDao, id = "g1", name = "Epic Fantasy")

                    val result = genreDao.getIdsByNames(listOf("epic fantasy", "EPIC FANTASY"))

                    withClue("COLLATE NOCASE dedups both spellings to the same row") { result.size shouldBe 1 }
                    result.first().id shouldBe "g1"
                    withClue("stored name preserves original casing") { result.first().name shouldBe "Epic Fantasy" }
                }
            } finally {
                db.close()
            }
        }

        test("getIdsByNames drops unknown names silently") {
            val db = createInMemoryTestDatabase()
            val genreDao = db.genreDao()
            try {
                runTest {
                    seedGenre(genreDao, id = "g1", name = "Fantasy")

                    val result = genreDao.getIdsByNames(listOf("Fantasy", "NoSuchGenre"))

                    result.size shouldBe 1
                    result.first().id shouldBe "g1"
                }
            } finally {
                db.close()
            }
        }

        test("getIdsByNames returns empty list for empty input") {
            val db = createInMemoryTestDatabase()
            val genreDao = db.genreDao()
            try {
                runTest {
                    seedGenre(genreDao, id = "g1", name = "Fantasy")

                    genreDao.getIdsByNames(emptyList()).isEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }
    })
