package com.calypsan.listenup.client.data.sync.domains

import com.calypsan.listenup.api.sync.SyncDomains
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.test.stubImageStorage
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.collections.shouldHaveSize

/**
 * Pins two invariants of the descriptor catalog as it grows: every entry's key is drawn
 * from the contract-level [SyncDomains] source of truth, and no two entries claim the
 * same wire name. Phase 2's closing plan upgrades this into the full 1:1:1 assertion
 * (`SyncDomains.all` ↔ catalog ↔ server registrations) once every domain is migrated;
 * until then it guards the three pilots against a duplicate or invented key.
 */
class SyncDomainCatalogSpec :
    FunSpec({
        test("catalog keys are unique and drawn from SyncDomains") {
            val db = createInMemoryTestDatabase()
            val catalog =
                syncDomainCatalog(
                    database = db,
                    mapper = BookEntityMapper(),
                    imageStorage = stubImageStorage(),
                    authSession = FakeAuthSession(userId = "spec-user"),
                )
            val names = catalog.mirrored.map { it.key.name }
            val known = SyncDomains.all.map { it.name }

            names.toSet() shouldHaveSize names.size
            names.forEach { it shouldBeIn known }
        }
    })
