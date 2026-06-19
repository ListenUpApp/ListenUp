package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.ContributorSyncPayload
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest

class ContributorRepositoryAliasReadWriteTest :
    FunSpec({

        test("should round-trip aliases through upsert and findById") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(
                        contributorPayloadFixture(
                            id = "contributor-roundtrip",
                            name = "Stephen King",
                            aliases = listOf("Richard Bachman", "John Swithen"),
                        ),
                    )

                    val readBack = repo.findById("contributor-roundtrip")

                    readBack.shouldNotBeNull()
                    readBack.aliases shouldContainExactlyInAnyOrder listOf("Richard Bachman", "John Swithen")
                }
            }
        }

        test("should clear aliases when upserted with empty list") {
            withSqlDatabase {
                val repo = ContributorRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    // Seed with aliases
                    repo.upsert(
                        contributorPayloadFixture(
                            id = "contributor-clear",
                            name = "Test Author",
                            aliases = listOf("alias-a", "alias-b"),
                        ),
                    )
                    // Overwrite with empty aliases
                    repo.upsert(
                        contributorPayloadFixture(
                            id = "contributor-clear",
                            name = "Test Author",
                            aliases = emptyList(),
                        ),
                    )

                    val readBack = repo.findById("contributor-clear")
                    readBack.shouldNotBeNull()
                    readBack.aliases.shouldBeEmpty()
                }
            }
        }
    })

private fun contributorPayloadFixture(
    id: String,
    name: String,
    sortName: String? = null,
    aliases: List<String> = emptyList(),
): ContributorSyncPayload =
    ContributorSyncPayload(
        id = id,
        name = name,
        sortName = sortName,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
        aliases = aliases,
    )
