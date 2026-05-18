package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.client.core.ContributorId
import com.calypsan.listenup.client.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Verifies [ContributorAliasDao] against a real in-memory [ListenUpDatabase].
 *
 * Exercises the SQL-level contracts: alphabetical ordering via `COLLATE NOCASE`,
 * foreign-key cascade on contributor delete, and `OnConflictStrategy.IGNORE`
 * for exact-case duplicates.
 */
class ContributorAliasDaoTest :
    FunSpec({
        lateinit var db: ListenUpDatabase
        lateinit var contributorDao: ContributorDao
        lateinit var aliasDao: ContributorAliasDao

        beforeTest {
            db = createInMemoryTestDatabase()
            contributorDao = db.contributorDao()
            aliasDao = db.contributorAliasDao()
        }

        afterTest {
            db.close()
        }

        test("insertAll and getForContributor returns aliases sorted alphabetically case-insensitively") {
            runTest {
                contributorAliasSeedContributor(contributorDao)

                aliasDao.insertAll(
                    listOf(
                        ContributorAliasCrossRef(ContributorId("c-1"), "richard bachman"),
                        ContributorAliasCrossRef(ContributorId("c-1"), "John Swithen"),
                        ContributorAliasCrossRef(ContributorId("c-1"), "Beryl Evans"),
                    ),
                )

                val result = aliasDao.getForContributor("c-1")

                result shouldBe listOf("Beryl Evans", "John Swithen", "richard bachman")
            }
        }

        test("getForContributor returns empty list when no aliases") {
            runTest {
                contributorAliasSeedContributor(contributorDao)
                aliasDao.getForContributor("c-1").isEmpty() shouldBe true
            }
        }

        test("deleteForContributor removes only that contributor's aliases") {
            runTest {
                contributorAliasSeedContributor(contributorDao, id = "c-1", name = "King")
                contributorAliasSeedContributor(contributorDao, id = "c-2", name = "Gaiman")

                aliasDao.insertAll(
                    listOf(
                        ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                        ContributorAliasCrossRef(ContributorId("c-2"), "Pinkerton"),
                    ),
                )

                aliasDao.deleteForContributor("c-1")

                aliasDao.getForContributor("c-1").isEmpty() shouldBe true
                aliasDao.getForContributor("c-2") shouldBe listOf("Pinkerton")
            }
        }

        test("cascade delete removes only the deleted contributor's aliases") {
            runTest {
                contributorAliasSeedContributor(contributorDao, id = "c-1", name = "King")
                contributorAliasSeedContributor(contributorDao, id = "c-2", name = "Gaiman")

                aliasDao.insertAll(
                    listOf(
                        ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                        ContributorAliasCrossRef(ContributorId("c-1"), "Swithen"),
                        ContributorAliasCrossRef(ContributorId("c-2"), "Pinkerton"),
                    ),
                )

                contributorDao.deleteById("c-1")

                aliasDao.getForContributor("c-1").isEmpty() shouldBe true
                aliasDao.getForContributor("c-2") shouldBe listOf("Pinkerton")
            }
        }

        test("observeForContributor emits initial list and re-emits on change") {
            runTest {
                contributorAliasSeedContributor(contributorDao)
                aliasDao.insertAll(
                    listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")),
                )

                aliasDao.observeForContributor("c-1").test {
                    awaitItem() shouldBe listOf("Bachman")

                    aliasDao.insertAll(
                        listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Swithen")),
                    )
                    awaitItem() shouldBe listOf("Bachman", "Swithen")

                    aliasDao.deleteForContributor("c-1")
                    awaitItem() shouldBe emptyList()

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("insertAll with duplicate exact-case alias is ignored") {
            runTest {
                contributorAliasSeedContributor(contributorDao)

                aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))
                aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))

                aliasDao.getForContributor("c-1") shouldBe listOf("Bachman")
            }
        }

        test("insertAll preserves case-different aliases as distinct rows") {
            runTest {
                contributorAliasSeedContributor(contributorDao)

                aliasDao.insertAll(
                    listOf(
                        ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                        ContributorAliasCrossRef(ContributorId("c-1"), "BACHMAN"),
                    ),
                )

                val result = aliasDao.getForContributor("c-1")
                result.size shouldBe 2
                ("Bachman" in result) shouldBe true
                ("BACHMAN" in result) shouldBe true
            }
        }

        test("observeByIdWithAliases returns contributor with aliases from junction") {
            runTest {
                contributorAliasSeedContributor(contributorDao)
                aliasDao.insertAll(
                    listOf(
                        ContributorAliasCrossRef(ContributorId("c-1"), "Swithen"),
                        ContributorAliasCrossRef(ContributorId("c-1"), "Bachman"),
                    ),
                )

                val result = contributorDao.getByIdWithAliases("c-1")

                result.shouldNotBeNull()
                result.contributor.id.value shouldBe "c-1"
                result.aliases shouldBe listOf("Bachman", "Swithen")
            }
        }

        test("getByIdWithAliases returns null for missing contributor") {
            runTest {
                contributorDao.getByIdWithAliases("no-such-id") shouldBe null
            }
        }
    })

private suspend fun contributorAliasSeedContributor(
    contributorDao: ContributorDao,
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
