package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [ContributorAliasDao] against a real in-memory [ListenUpDatabase].
 *
 * Exercises the SQL-level contracts: alphabetical ordering via `COLLATE NOCASE`,
 * foreign-key cascade on contributor delete, and `OnConflictStrategy.IGNORE`
 * for exact-case duplicates.
 */
class ContributorAliasDaoTest {
    private val db: ListenUpDatabase = createInMemoryTestDatabase()
    private val contributorDao = db.contributorDao()
    private val aliasDao = db.contributorAliasDao()

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private suspend fun seedContributor(
        id: String = "c-1",
        name: String = "Stephen King",
    ) {
        contributorDao.upsert(
            ContributorEntity(
                id = ContributorId(id),
                name = name,
                description = null,
                imagePath = null,
                createdAt = Timestamp(1L),
                updatedAt = Timestamp(1L),
            ),
        )
    }

    @Test
    fun `insertAll and getForContributor returns aliases sorted alphabetically case-insensitively`() =
        runTest {
            seedContributor()

            aliasDao.insertAll(
                listOf(
                    ContributorAliasCrossRef(ContributorId("c-1"), "richard bachman"),
                    ContributorAliasCrossRef(ContributorId("c-1"), "John Swithen"),
                    ContributorAliasCrossRef(ContributorId("c-1"), "Beryl Evans"),
                ),
            )

            val result = aliasDao.getForContributor("c-1")

            assertEquals(listOf("Beryl Evans", "John Swithen", "richard bachman"), result)
        }

    @Test
    fun `getForContributor returns empty list when no aliases`() =
        runTest {
            seedContributor()
            assertTrue(aliasDao.getForContributor("c-1").isEmpty())
        }

    @Test
    fun `deleteForContributor removes only that contributor's aliases`() =
        runTest {
            seedContributor(id = "c-1", name = "King")
            seedContributor(id = "c-2", name = "Gaiman")

            aliasDao.insertAll(
                listOf(
                    ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                    ContributorAliasCrossRef(ContributorId("c-2"), "Pinkerton"),
                ),
            )

            aliasDao.deleteForContributor("c-1")

            assertTrue(aliasDao.getForContributor("c-1").isEmpty())
            assertEquals(listOf("Pinkerton"), aliasDao.getForContributor("c-2"))
        }

    @Test
    fun `cascade delete removes only the deleted contributor's aliases`() =
        runTest {
            seedContributor(id = "c-1", name = "King")
            seedContributor(id = "c-2", name = "Gaiman")

            aliasDao.insertAll(
                listOf(
                    ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                    ContributorAliasCrossRef(ContributorId("c-1"), "Swithen"),
                    ContributorAliasCrossRef(ContributorId("c-2"), "Pinkerton"),
                ),
            )

            contributorDao.deleteById("c-1")

            assertTrue(aliasDao.getForContributor("c-1").isEmpty())
            assertEquals(listOf("Pinkerton"), aliasDao.getForContributor("c-2"))
        }

    @Test
    fun `observeForContributor emits initial list and re-emits on change`() =
        runTest {
            seedContributor()
            aliasDao.insertAll(
                listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")),
            )

            aliasDao.observeForContributor("c-1").test {
                assertEquals(listOf("Bachman"), awaitItem())

                aliasDao.insertAll(
                    listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Swithen")),
                )
                assertEquals(listOf("Bachman", "Swithen"), awaitItem())

                aliasDao.deleteForContributor("c-1")
                assertEquals(emptyList(), awaitItem())

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `insertAll with duplicate exact-case alias is ignored`() =
        runTest {
            seedContributor()

            aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))
            aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))

            assertEquals(listOf("Bachman"), aliasDao.getForContributor("c-1"))
        }

    @Test
    fun `insertAll preserves case-different aliases as distinct rows`() =
        runTest {
            seedContributor()

            aliasDao.insertAll(
                listOf(
                    ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                    ContributorAliasCrossRef(ContributorId("c-1"), "BACHMAN"),
                ),
            )

            val result = aliasDao.getForContributor("c-1")
            assertEquals(2, result.size)
            assertTrue("Bachman" in result)
            assertTrue("BACHMAN" in result)
        }

    @Test
    fun `observeByIdWithAliases returns contributor with aliases from junction`() =
        runTest {
            seedContributor()
            aliasDao.insertAll(
                listOf(
                    ContributorAliasCrossRef(ContributorId("c-1"), "Swithen"),
                    ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                ),
            )

            val result = contributorDao.getByIdWithAliases("c-1")

            kotlin.test.assertNotNull(result)
            assertEquals("c-1", result.contributor.id.value)
            assertEquals(listOf("Bachman", "Swithen"), result.aliases)
        }

    @Test
    fun `getByIdWithAliases returns null for missing contributor`() =
        runTest {
            assertEquals(null, contributorDao.getByIdWithAliases("no-such-id"))
        }
}
