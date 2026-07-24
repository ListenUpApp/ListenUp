package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.sync.testing.registerTestSyncDomains
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest

/**
 * Pins the exact set of production handlers that opt out of digest reconciliation.
 *
 * A handler opts out by returning `null` from [SyncDomainHandler.localDigestRows].
 * An empty DB makes a reconcilable domain return `emptyList()` (non-null), and an
 * opted-out domain return `null` — so "opted out" == returns null against an empty DB.
 *
 * If a future domain silently can't be fingerprinted (another C1-class bug — server uses
 * an id the client never stores), this test will fail the build instead of the domain
 * looping forever on every reconnect.
 */
class DigestOptOutSetTest :
    FunSpec({

        test("exactly playback_positions opts out of digest reconciliation") {
            runTest {
                val clientDb = createInMemoryTestDatabase()
                try {
                    val registry = ClientSyncDomainRegistry()

                    // Register every production handler via the real catalog, so this test
                    // tracks production exactly (no hand-listed subset to drift).
                    registerTestSyncDomains(db = clientDb, registry = registry)

                    // Collect the domains whose handler returns null from localDigestRows against
                    // an empty DB. An empty DB ensures reconcilable domains return emptyList() (non-null)
                    // and opted-out domains return null — so null == opted out.
                    val optedOut =
                        registry
                            .registeredDomains()
                            .filter { domain ->
                                val handler = registry.lookup(domain)!!
                                handler.localDigestRows(Long.MAX_VALUE) == null
                            }.toSet()

                    optedOut shouldBe setOf("playback_positions")
                } finally {
                    clientDb.close()
                }
            }
        }
    })
