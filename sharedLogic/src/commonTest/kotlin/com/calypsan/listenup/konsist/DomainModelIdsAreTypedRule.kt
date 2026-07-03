package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Pin: domain-model files that have completed the value-class id migration must
 * not regress to raw `String` id properties.
 *
 * [MIGRATED_DOMAIN_MODELS] grows one entry per migrated domain — adding a file
 * here is the explicit signal that its domain's ids are typed end-to-end (the
 * `id`/`*Id` properties are `@JvmInline value class` wrappers from
 * `contract/.../core/ValueClasses.kt`, not `String`).
 *
 * [ALLOWED_STRING_ID_NAMES] lists id-suffixed properties that legitimately stay
 * `String` until THEIR owning domain migrates (e.g. `ownerId` is a user id and
 * migrates with the User domain). The `id`/`idString` split is deliberate: the
 * typed identity is `id`; `idString` is the plain-`String` Swift/SKIE bridge and
 * is intentionally excluded (`name == "id"` only, never `idString`).
 */
class DomainModelIdsAreTypedRule :
    FunSpec({
        test("migrated domain models use value-class ids, not String") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { cls -> MIGRATED_DOMAIN_MODELS.any { cls.path.endsWith(it) } }
                    .flatMap { cls ->
                        cls
                            .properties()
                            .filter {
                                it.name == "id" || (
                                    it.name.endsWith(
                                        "Id",
                                    ) && it.name !in ALLOWED_STRING_ID_NAMES
                                )
                            }.filter { it.type?.name == "String" }
                            .map { "${cls.name}.${it.name} in ${it.path}" }
                    }

            offenders.shouldBeEmpty()
        }
    })

/**
 * Domain-model files whose ids are typed end-to-end. Extending this set is the
 * "domain done" signal for the value-class id migration (Plan 069 onward).
 */
private val MIGRATED_DOMAIN_MODELS =
    setOf(
        "domain/model/Shelf.kt",
        "domain/model/ShelfDetail.kt",
    )

/**
 * Id-suffixed properties that legitimately remain `String` on a migrated model
 * because they belong to a domain that has not migrated yet (removed as each
 * owning domain is typed).
 */
private val ALLOWED_STRING_ID_NAMES = setOf("ownerId")
