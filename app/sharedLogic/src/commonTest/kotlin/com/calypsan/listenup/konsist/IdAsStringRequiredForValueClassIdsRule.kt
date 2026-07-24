package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

/**
 * Pin: any concrete `SqlSyncableRepository<T, ID>` subclass whose `ID` type argument is a
 * `@JvmInline value class` MUST override `idAsString(id: ID): String` to return the raw
 * underlying value.
 *
 * Without the override the base default is `id.toString()`, which on a value class returns
 * `"BookId(value=foo)"`. That string lands in the primary-key column, every WHERE clause and the
 * `SyncEvent.id` payload — corrupting the table and breaking every soft-delete and event-echo
 * match. String-id domains are exempt: `String.toString()` is the identity, so the default is
 * correct.
 *
 * **Why the rule is re-keyed.** It used to match the Exposed-era base name `SyncableRepository`.
 * The substrate migrated to SQLDelight, the base became `SqlSyncableRepository`, and this rule
 * matched zero declarations and passed green — while 15 repositories shipped relying purely on
 * hand-written `idAsString` overrides. Its KDoc still claimed "no production repository has a
 * value-class id — the rule passes trivially". Discovery now lives in [syncableRepositories] and
 * the vacuity guard below fails the build if it ever stops matching.
 */
class IdAsStringRequiredForValueClassIdsRule :
    FunSpec({

        test("every concrete SqlSyncableRepository with a @JvmInline value-class ID overrides idAsString") {
            val repositories = syncableRepositories()

            // Vacuity guards: prove we found repositories at all, and that at least one has a
            // value-class id — i.e. that this rule has live subjects rather than passing on air.
            repositories.shouldNotBeEmpty()
            repositories.filter { it.valueClassIdName != null }.shouldNotBeEmpty()

            val offenders =
                repositories.mapNotNull { repo ->
                    val idName = repo.valueClassIdName ?: return@mapNotNull null
                    if (repo.overridesIdAsString) return@mapNotNull null
                    "${repo.name} uses value-class id $idName but does not override idAsString " +
                        "(the default `id.toString()` would write \"$idName(value=...)\" into the " +
                        "primary key, every WHERE clause and every SyncEvent.id)"
                }

            offenders.shouldBeEmpty()
        }
    })
