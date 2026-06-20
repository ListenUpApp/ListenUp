package com.calypsan.listenup.server.sync

import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Pins that constructing the three collection [SyncableRepository]s registers their sync
 * domains with the [SyncRegistry]. Each repository's `init` block calls
 * `registry.register(this)`, so the act of constructing them must make
 * `"collections"`, `"collection_books"`, and `"collection_shares"` visible on
 * `SyncRegistry.knownDomains()` — the data backing `/api/v1/sync/domains`.
 *
 * A regression here (a repo that forgets to register, or a domainName typo) would silently
 * drop a collection domain from the sync firehose; this test catches it at the substrate.
 */
class CollectionRegistryTest :
    FunSpec({
        test("constructing the collection repositories registers all three sync domains") {
            withInMemoryDatabase {
                val db: Database = this
                val bus = ChangeBus()
                val registry = SyncRegistry()

                CollectionRepository(db, bus, registry)
                CollectionBookRepository(db, bus, registry)
                CollectionGrantRepository(db, bus, registry)

                registry.knownDomains() shouldContainAll
                    listOf("collections", "collection_books", "collection_shares")
            }
        }
    })
