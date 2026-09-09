package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll

/**
 * Konsist guards for the embeddedmeta package boundary:
 *
 *  1. Every domain type that crosses the wire — RPC service signatures return
 *     these — lives in commonMain. Domain types in a
 *     server-only source set would force a wire-shape duplicate when the
 *     books domain migrates.
 *  2. Concrete `AudioFormatParser` implementations live under
 *     `com.calypsan.listenup.server.embeddedmeta.*` so the contributor
 *     doc's add-a-format flow has one canonical home.
 *  3. Every `AudioFormatParser` declares a non-empty `supports`. The
 *     runtime contract test is the real safety net for
 *     this — Konsist's heuristic catches the obvious `setOf()` mistake
 *     but is structurally limited.
 */
class EmbeddedMetaTypesInCommonMainRule :
    FunSpec({

        // No assertScopeNotEmpty guard on this first test: it asserts something POSITIVE about the
        // scope (shouldContainAll a fixed 7-FQN set), so a collapsed scope makes `foundInCommonMain`
        // empty and turns it RED. A count guard would only restate what the assertion already does.
        test("embeddedmeta domain types live in commonMain") {
            val expectedTypes =
                setOf(
                    "com.calypsan.listenup.domain.embeddedmeta.AudioFormat",
                    "com.calypsan.listenup.domain.embeddedmeta.AudioTags",
                    "com.calypsan.listenup.domain.embeddedmeta.Chapter",
                    "com.calypsan.listenup.domain.embeddedmeta.ChapterSource",
                    "com.calypsan.listenup.domain.embeddedmeta.EmbeddedArtwork",
                    "com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata",
                    "com.calypsan.listenup.domain.embeddedmeta.SeriesEntry",
                )
            // Sealed interfaces (`AudioFormat`, `ChapterSource`) live alongside data classes,
            // so both Konsist accessors must contribute to the FQN set.
            val scope = productionScope()
            val foundInCommonMain =
                (scope.classes() + scope.interfaces())
                    .filter { it.path.contains("/commonMain/") }
                    .mapNotNull { it.fullyQualifiedName }
                    .toSet()

            foundInCommonMain shouldContainAll expectedTypes
        }

        test("AudioFormatParser implementations live under :server.embeddedmeta") {
            val implementations =
                productionScope()
                    .classes()
                    .withoutAbstractModifier()
                    .filter { cls ->
                        cls.parents().any { it.name.bareTypeName() == "AudioFormatParser" }
                    }

            assertScopeNotEmpty(
                implementations,
                expectedMin = 1,
                why =
                    "concrete AudioFormatParser implementations. A small fixed set (2 today, one per audio " +
                        "container), so this floor is a pure collapse detector — do not raise it toward the count.",
            )

            val misplaced =
                implementations.filterNot { cls ->
                    cls.fullyQualifiedName?.startsWith("com.calypsan.listenup.server.embeddedmeta.") ?: false
                }
            misplaced.map { it.fullyQualifiedName }.shouldBeEmpty()
        }

        test("every AudioFormatParser implementation declares non-empty supports") {
            val implementations =
                productionScope()
                    .classes()
                    .withoutAbstractModifier()
                    .filter { cls ->
                        cls.parents().any { it.name.bareTypeName() == "AudioFormatParser" }
                    }

            assertScopeNotEmpty(
                implementations,
                expectedMin = 1,
                why =
                    "concrete AudioFormatParser implementations. A small fixed set (2 today, one per audio " +
                        "container), so this floor is a pure collapse detector — do not raise it toward the count.",
            )

            val violators =
                implementations.filter { cls ->
                    val supportsProperty = cls.properties().firstOrNull { it.name == "supports" }
                    supportsProperty == null ||
                        supportsProperty.text.let { src ->
                            src.contains("setOf(") &&
                                src.substringAfter("setOf(").substringBefore(")").isBlank()
                        }
                }
            violators.map { it.fullyQualifiedName }.shouldBeEmpty()
        }
    })
