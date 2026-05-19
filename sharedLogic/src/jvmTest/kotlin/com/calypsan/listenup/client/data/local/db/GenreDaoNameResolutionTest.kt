package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [GenreDao.getIdsByNames] against a real in-memory [ListenUpDatabase].
 *
 * Covers the resolution primitive sync domain handlers use to map
 * server-sent genre names to local genre IDs. Case-insensitive via `COLLATE NOCASE`;
 * unknown names simply don't appear in the result set.
 */
class GenreDaoNameResolutionTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val genreDao = db.genreDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedGenre(
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
                    bookCount = 0,
                    parentId = null,
                    depth = 0,
                    sortOrder = 0,
                ),
            ),
        )
    }

    @Test
    fun `getIdsByNames returns matching id-name pairs`() =
        runTest {
            seedGenre(id = "g1", name = "Fantasy")
            seedGenre(id = "g2", name = "Science Fiction")
            seedGenre(id = "g3", name = "Horror")

            val result = genreDao.getIdsByNames(listOf("Fantasy", "Horror"))

            assertEquals(2, result.size)
            assertEquals(setOf("g1", "g3"), result.map { it.id }.toSet())
        }

    @Test
    fun `getIdsByNames matches case-insensitively`() =
        runTest {
            seedGenre(id = "g1", name = "Epic Fantasy")

            val result = genreDao.getIdsByNames(listOf("epic fantasy", "EPIC FANTASY"))

            assertEquals(1, result.size, "COLLATE NOCASE dedups both spellings to the same row")
            assertEquals("g1", result.first().id)
            assertEquals("Epic Fantasy", result.first().name, "stored name preserves original casing")
        }

    @Test
    fun `getIdsByNames drops unknown names silently`() =
        runTest {
            seedGenre(id = "g1", name = "Fantasy")

            val result = genreDao.getIdsByNames(listOf("Fantasy", "NoSuchGenre"))

            assertEquals(1, result.size)
            assertEquals("g1", result.first().id)
        }

    @Test
    fun `getIdsByNames returns empty list for empty input`() =
        runTest {
            seedGenre(id = "g1", name = "Fantasy")

            assertTrue(genreDao.getIdsByNames(emptyList()).isEmpty())
        }
}
