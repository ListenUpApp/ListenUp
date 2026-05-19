package com.calypsan.listenup.client.data.local.db

import app.cash.turbine.test
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.Timestamp
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

        test("insertAll and getForContributor returns aliases sorted alphabetically case-insensitively") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("getForContributor returns empty list when no aliases") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
                    contributorAliasSeedContributor(contributorDao)
                    aliasDao.getForContributor("c-1").isEmpty() shouldBe true
                }
            } finally {
                db.close()
            }
        }

        test("deleteForContributor removes only that contributor's aliases") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("cascade delete removes only the deleted contributor's aliases") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("observeForContributor emits initial list and re-emits on change") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("insertAll with duplicate exact-case alias is ignored") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
                    contributorAliasSeedContributor(contributorDao)

                    aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))
                    aliasDao.insertAll(listOf(ContributorAliasCrossRef(ContributorId("c-1"), "Bachman")))

                    aliasDao.getForContributor("c-1") shouldBe listOf("Bachman")
                }
            } finally {
                db.close()
            }
        }

        test("insertAll preserves case-different aliases as distinct rows") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("observeByIdWithAliases returns contributor with aliases from junction") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    val aliasDao = db.contributorAliasDao()
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
            } finally {
                db.close()
            }
        }

        test("getByIdWithAliases returns null for missing contributor") {
            val db = createInMemoryTestDatabase()
            try {
                runTest {
                    val contributorDao = db.contributorDao()
                    contributorDao.getByIdWithAliases("no-such-id") shouldBe null
                }
            } finally {
                db.close()
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
