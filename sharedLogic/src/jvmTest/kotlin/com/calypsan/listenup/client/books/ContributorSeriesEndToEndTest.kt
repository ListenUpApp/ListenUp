package com.calypsan.listenup.client.books

import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests proving `contributors` and `series` sync as first-class domains.
 *
 * Each test calls [com.calypsan.listenup.server.services.ContributorRepository.resolveOrCreate]
 * or [com.calypsan.listenup.server.services.SeriesRepository.resolveOrCreate] — the same
 * operation a scan performs — which publishes a [com.calypsan.listenup.server.sync.SyncEvent]
 * on the server. The event crosses the live SSE firehose, the client
 * [com.calypsan.listenup.client.data.sync.SyncEngine] routes it through the real
 * [com.calypsan.listenup.client.data.sync.domains.contributorsDomain] /
 * [com.calypsan.listenup.client.data.sync.domains.seriesDomain], and the
 * row lands in the client's Room database — exactly the round-trip production performs.
 *
 * A non-zero `revision` on the landed row is the key invariant: it proves the row
 * arrived through the domain-specific sync handler rather than as a `revision = 0`
 * bootstrap stub inserted by [com.calypsan.listenup.client.data.sync.domains.BookMirrorApply].
 */
class ContributorSeriesEndToEndTest :
    FunSpec({

        test("server resolveOrCreate contributor → SSE → client Room has contributor with non-zero revision") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val contributorId =
                    serverContributorRepository.resolveOrCreate("Brandon Sanderson", sortName = null)

                val contributor =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row =
                            clientDatabase.contributorDao().getById(contributorId.value)
                        while (row == null) {
                            row = clientDatabase.contributorDao().getById(contributorId.value)
                        }
                        row
                    }

                contributor shouldNotBe null
                contributor.name shouldBe "Brandon Sanderson"
                contributor.revision shouldBeGreaterThan 0L
            }
        }

        test("server resolveOrCreate series → SSE → client Room has series with non-zero revision") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val seriesId =
                    serverSeriesRepository.resolveOrCreate("The Stormlight Archive")

                val series =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = clientDatabase.seriesDao().getById(seriesId.value)
                        while (row == null) {
                            row = clientDatabase.seriesDao().getById(seriesId.value)
                        }
                        row
                    }

                series shouldNotBe null
                series.name shouldBe "The Stormlight Archive"
                series.revision shouldBeGreaterThan 0L
            }
        }
    })
