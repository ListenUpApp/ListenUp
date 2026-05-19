package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pin: any concrete `SyncableRepository<T, ID>` subclass whose `ID` type
 * argument is a `@JvmInline value class` MUST override `idAsString(id: ID):
 * String` to return the raw underlying value.
 *
 * Without the override, the default impl in [SyncableRepository] is
 * `id.toString()` — which on a `@JvmInline value class` returns
 * `"WrapperName(value=foo)"`. That string would land in the primary-key
 * column, every WHERE clause, and the `SyncEvent.id` payload — corrupting
 * the table and breaking every soft-delete and event-echo match.
 *
 * String-id domains (e.g., `TagRepository : SyncableRepository<Tag, String>`)
 * are exempt: `String.toString()` is the identity, so the default impl is
 * correct. The rule's predicate skips them.
 *
 * As of the initial roll-out, no production repository has a value-class id
 * — the rule passes trivially. It exists to fire the moment Books-A introduces
 * `BookRepository : SyncableRepository<Book, BookId>` and catches a missing
 * override at build time.
 */
class IdAsStringRequiredForValueClassIdsRule :
    FunSpec({

        test("every concrete SyncableRepository with a @JvmInline value-class ID overrides idAsString") {
            // Konsist 0.17.3 includes type arguments in `parent.name`, so a TagRepository's
            // SyncableRepository<Tag, String> parent appears under the name
            // "SyncableRepository<Tag, String>". Match by the type-argument-stripped name.
            fun String.bareTypeName(): String = substringBefore('<')

            val concreteRepositories =
                Konsist
                    .scopeFromProduction()
                    .classes()
                    .withoutAbstractModifier()
                    .filter { cls ->
                        cls.parents().any { it.name.bareTypeName() == "SyncableRepository" }
                    }

            val offenders =
                concreteRepositories.mapNotNull { repo ->
                    val parent = repo.parents().first { it.name.bareTypeName() == "SyncableRepository" }
                    // SyncableRepository<T, ID> — ID is the 2nd type argument.
                    val idArg = parent.typeArguments?.getOrNull(1) ?: return@mapNotNull null
                    val idClass = idArg.sourceDeclaration?.asClassDeclaration() ?: return@mapNotNull null
                    if (!idClass.hasValueModifier) {
                        // String-id and other reference-type-id domains: default impl is fine.
                        return@mapNotNull null
                    }
                    val hasOverride =
                        repo.functions().any { fn ->
                            fn.name == "idAsString" && fn.hasOverrideModifier
                        }
                    if (hasOverride) {
                        null
                    } else {
                        "${repo.name} uses value-class id ${idClass.name} but does not override idAsString " +
                            "(default `id.toString()` would write \"${idClass.name}(value=...)\" to every column)"
                    }
                }

            offenders.shouldBeEmpty()
        }
    })
