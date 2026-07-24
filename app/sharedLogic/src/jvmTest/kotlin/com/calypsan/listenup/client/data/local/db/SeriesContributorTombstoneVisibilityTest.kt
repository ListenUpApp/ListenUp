package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Series and Contributor are soft-deletable sync domains: their handlers apply a
 * server tombstone by setting `deletedAt`, and every sibling domain's reactive UI
 * reads filter `deletedAt IS NULL`. These two had drifted — their `observe*`
 * queries selected unconditionally, so a server-deleted series/contributor stayed
 * visible (and tappable) in author/narrator lists and series shelves forever.
 *
 * Pins both halves of the contract:
 *  - reactive UI reads (`observe*`) hide tombstoned rows, and
 *  - the suspend `getById` (the sync handler's read-modify-write) still sees them,
 *    so tombstone/un-tombstone events apply correctly.
 */
class SeriesContributorTombstoneVisibilityTest :
    FunSpec({

        test("SeriesDao.observeAll hides a tombstoned series but getById still sees it") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.seriesDao()
                    dao.upsert(seriesEntity("s-live", "Stormlight Archive"))
                    dao.upsert(seriesEntity("s-dead", "Removed Series"))
                    dao.softDelete(SeriesId("s-dead"), deletedAt = 1_000L, revision = 2L)

                    dao.observeAll().first().map { it.id.value } shouldContainExactly listOf("s-live")
                    // The sync handler's RMW read must still resolve the tombstoned row.
                    dao.getById("s-dead").shouldNotBeNull()
                } finally {
                    db.close()
                }
            }
        }

        test("ContributorDao.observeAll hides a tombstoned contributor but getById still sees it") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    val dao = db.contributorDao()
                    dao.upsert(contributorEntity("c-live", "Brandon Sanderson"))
                    dao.upsert(contributorEntity("c-dead", "Removed Author"))
                    dao.softDelete(ContributorId("c-dead"), deletedAt = 1_000L, revision = 2L)

                    dao.observeAll().first().map { it.id.value } shouldContainExactly listOf("c-live")
                    dao.getById("c-dead").shouldNotBeNull()
                } finally {
                    db.close()
                }
            }
        }
    })

private fun seriesEntity(
    id: String,
    name: String,
) = SeriesEntity(
    id = SeriesId(id),
    name = name,
    description = null,
    createdAt = Timestamp(1L),
    updatedAt = Timestamp(1L),
)

private fun contributorEntity(
    id: String,
    name: String,
) = ContributorEntity(
    id = ContributorId(id),
    name = name,
    description = null,
    imagePath = null,
    createdAt = Timestamp(1L),
    updatedAt = Timestamp(1L),
)
