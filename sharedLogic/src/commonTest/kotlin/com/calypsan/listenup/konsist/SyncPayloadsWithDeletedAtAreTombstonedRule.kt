package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Tombstone routing is a wire-protocol concern, not a per-domain switch: the
 * REST catch-up loop and every other consumer that distinguishes "current row"
 * from "soft-delete sentinel" keys off the [com.calypsan.listenup.api.sync.Tombstoned]
 * marker, not the presence of a `deletedAt` field. A sync payload that carries
 * `deletedAt` but omits the marker silently opts out of the tombstone path — its
 * deletes only survive by accident through the upsert branch, and the REST
 * catch-up `isTombstone` check is permanently false for that domain.
 *
 * This rule pins the invariant structurally: **every `@Serializable` payload in
 * `api.sync` that declares a `deletedAt` property must implement `Tombstoned`** —
 * directly or transitively via `SyncPayload`, which extends it. It caught
 * `GenreSyncPayload`, the one domain that had drifted off the marker.
 */
class SyncPayloadsWithDeletedAtAreTombstonedRule :
    FunSpec({

        test("every api.sync payload with deletedAt implements Tombstoned") {
            val syncPayloadsWithDeletedAt =
                productionScope()
                    .classes()
                    .filter { it.fullyQualifiedName?.startsWith("com.calypsan.listenup.api.sync.") == true }
                    .filter { cls -> cls.properties().any { it.name == "deletedAt" } }

            // Guard against a vacuous pass: there must be sync payloads to check.
            syncPayloadsWithDeletedAt.shouldNotBeEmpty()

            val missingMarker =
                syncPayloadsWithDeletedAt
                    .filterNot { cls ->
                        cls.parents(indirectParents = true).any { it.name == "Tombstoned" }
                    }.map { it.name }

            missingMarker.shouldBeEmpty()
        }
    })
