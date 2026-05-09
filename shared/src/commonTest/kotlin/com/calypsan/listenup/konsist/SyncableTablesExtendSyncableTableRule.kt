package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pin: any concrete `SyncableRepository` subclass in `:server/.../sync/` whose
 * name is `<Domain>Repository` must be paired with a `<Domain>Table` object that
 * extends `SyncableTable`.
 *
 * This backstops the substrate convention so future domains don't accidentally
 * bypass the sync columns (`revision`, `created_at`, `updated_at`,
 * `deleted_at`, `client_op_id`) that the catch-up and digest routes depend on.
 *
 * The rule deliberately targets the `:server/.../sync/` path — that's where
 * domain repositories live. The abstract `SyncableRepository` base class is
 * excluded via `withoutAbstractModifier()`.
 */
class SyncableTablesExtendSyncableTableRule :
    FunSpec({

        test("Tables paired with a SyncableRepository extend SyncableTable") {
            val scope = Konsist.scopeFromProduction()

            // Concrete classes in server/sync/ that extend SyncableRepository.
            val concreteRepositories =
                scope
                    .classes()
                    .withoutAbstractModifier()
                    .filter { cls ->
                        cls.path.contains("/server/") &&
                            cls.path.contains("/sync/") &&
                            cls.parents().any { it.name == "SyncableRepository" }
                    }

            // All Exposed table objects in the production scope.
            val tableObjects = scope.objects()

            val offenders =
                concreteRepositories.mapNotNull { repoClass ->
                    val base = repoClass.name.removeSuffix("Repository")
                    val expectedTableName = "${base}Table"
                    val tableObj =
                        tableObjects.firstOrNull { it.name == expectedTableName }
                            ?: return@mapNotNull "$expectedTableName not found (expected pair for ${repoClass.name})"
                    val extendsSyncableTable =
                        tableObj.parents().any { it.name == "SyncableTable" }
                    if (extendsSyncableTable) {
                        null
                    } else {
                        "$expectedTableName does not extend SyncableTable " +
                            "(parents: ${tableObj.parents().map { it.name }})"
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
