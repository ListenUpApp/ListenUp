package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pin: any concrete `SyncableRepository<T, ID>` subclass whose paired table
 * extends `UserScopedSyncableTable` MUST override `userScoped` to return `true`.
 *
 * Without the override, the base class defaults `userScoped = false` — the
 * repository executes reads and digests globally, leaking every user's rows to
 * every other user. A per-user table read without a user filter is exactly the
 * data-isolation bug this rule prevents.
 *
 * **Structural check and its limitation.**
 * Konsist operates on declaration structure, not runtime types. The
 * `SyncableRepository` constructor accepts `table: SyncableTable`, so the static
 * type of the constructor argument is always `SyncableTable` — we cannot read the
 * concrete type of the actual argument from the AST alone. Instead this rule uses
 * the codebase naming convention: a repository named `<Domain>Repository` is paired
 * with a table object named `<Domain>Table`. If that table object's parent class
 * is `UserScopedSyncableTable`, the repository is per-user and must declare
 * `override val userScoped = true`.
 *
 * The check degrades gracefully:
 * - If no paired `<Domain>Table` is found the repository is skipped with a warning
 *   (domains with non-standard table names like `BookRepository` → `BookTable`
 *   resolved from `server/services/` are still caught when they follow the convention).
 * - If the paired table doesn't extend `UserScopedSyncableTable`, the repository
 *   is a global domain and skipped.
 * - Repositories that deviate from the `<Domain>Repository` / `<Domain>Table`
 *   naming convention must be audited manually when they are introduced.
 *
 * **Current state.**
 * As of the initial roll-out (Playback-P1 Task 6), no production per-user
 * repository exists — `PlaybackPositionRepository` arrives in Task 8. The rule
 * passes vacuously today and fires the moment a mis-declared per-user repository
 * is added.
 */
class UserScopedRepositoryScopesByUserRule :
    FunSpec({

        test("every concrete SyncableRepository backed by a UserScopedSyncableTable overrides userScoped to true") {
            fun String.bareTypeName(): String = substringBefore('<')

            val scope = Konsist.scopeFromProduction()

            // Concrete SyncableRepository subclasses in :server production code.
            val concreteRepositories =
                scope
                    .classes()
                    .withoutAbstractModifier()
                    .filter { cls ->
                        cls.path.contains("/server/") &&
                            cls.parents().any { it.name.bareTypeName() == "SyncableRepository" }
                    }

            // All Exposed table objects in the production scope — used for paired-table lookup.
            val allObjects = scope.objects()

            val offenders =
                concreteRepositories.mapNotNull { repo ->
                    // Derive the expected paired table name from the repository class name.
                    val base = repo.name.removeSuffix("Repository")
                    val expectedTableName = "${base}Table"

                    val tableObj =
                        allObjects.firstOrNull { it.name == expectedTableName }
                            ?: return@mapNotNull null  // Non-standard naming: skip (document manually)

                    val isUserScoped =
                        tableObj.parents().any { it.name.bareTypeName() == "UserScopedSyncableTable" }
                    if (!isUserScoped) {
                        // Global domain — userScoped = false is correct.
                        return@mapNotNull null
                    }

                    // Per-user domain: the repository MUST override userScoped to true.
                    val overridesUserScopedTrue =
                        repo.properties().any { prop ->
                            prop.name == "userScoped" &&
                                prop.hasOverrideModifier &&
                                prop.text.contains("true")
                        }

                    if (overridesUserScopedTrue) {
                        null
                    } else {
                        "${repo.name} is backed by UserScopedSyncableTable (${tableObj.name}) " +
                            "but does not override userScoped = true — " +
                            "its rows would be read globally, leaking across users"
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
