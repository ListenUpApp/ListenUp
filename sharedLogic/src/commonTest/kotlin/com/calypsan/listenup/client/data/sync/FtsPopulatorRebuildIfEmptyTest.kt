package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.BookDao
import com.calypsan.listenup.client.data.local.db.ContributorDao
import com.calypsan.listenup.client.data.local.db.SearchDao
import com.calypsan.listenup.client.data.local.db.SeriesDao
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import kotlinx.coroutines.test.runTest

/**
 * Tests for [FtsPopulator.rebuildIfEmpty] — the startup self-heal that (re)builds the local search
 * index only when it is empty. This is what makes search work for an install whose library is
 * already in Room but whose FTS index was never populated: the next launch detects the empty index
 * and rebuilds it, with no wasteful rebuild when the index is already populated.
 */
class FtsPopulatorRebuildIfEmptyTest :
    FunSpec({

        val passThrough =
            object : TransactionRunner {
                override suspend fun <R> atomically(block: suspend () -> R): R = block()
            }

        fun populator(
            searchDao: SearchDao,
            bookDao: BookDao = mock { everySuspend { getAllLive() } returns emptyList() },
            contributorDao: ContributorDao = mock { everySuspend { getAllWithAliases() } returns emptyList() },
            seriesDao: SeriesDao = mock { everySuspend { getAll() } returns emptyList() },
        ) = FtsPopulator(
            bookDao = bookDao,
            contributorDao = contributorDao,
            seriesDao = seriesDao,
            searchDao = searchDao,
            transactionRunner = passThrough,
        )

        test("rebuildIfEmpty rebuilds the index when it is empty") {
            runTest {
                val searchDao =
                    mock<SearchDao> {
                        everySuspend { countBooksFts() } returns 0
                        everySuspend { clearBooksFts() } returns Unit
                        everySuspend { clearContributorsFts() } returns Unit
                        everySuspend { clearSeriesFts() } returns Unit
                        everySuspend { getAllPrimaryAuthorNames() } returns emptyList()
                        everySuspend { getAllPrimaryNarratorNames() } returns emptyList()
                        everySuspend { getAllSeriesNamesGrouped() } returns emptyList()
                        everySuspend { getAllGenreNamesGrouped() } returns emptyList()
                    }

                populator(searchDao).rebuildIfEmpty()

                // An empty index → a full rebuild runs (clears the tables on its way to repopulating).
                verifySuspend { searchDao.clearBooksFts() }
            }
        }

        test("rebuildIfEmpty does nothing when the index is already populated") {
            runTest {
                val searchDao =
                    mock<SearchDao> {
                        everySuspend { countBooksFts() } returns 42
                    }

                populator(searchDao).rebuildIfEmpty()

                // No rebuild — the clear/insert path must not run when the index already has rows.
                verifySuspend(VerifyMode.not) { searchDao.clearBooksFts() }
            }
        }
    })
