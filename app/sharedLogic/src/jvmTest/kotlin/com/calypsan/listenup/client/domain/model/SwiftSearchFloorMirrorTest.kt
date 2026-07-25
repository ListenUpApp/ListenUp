package com.calypsan.listenup.client.domain.model

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Pins the iOS mirror of [MIN_SEARCH_QUERY_LENGTH] to the Kotlin original.
 *
 * The floor is a Kotlin top-level `const val`, and a top-level constant is not a *type*, so it does
 * not cross the flat-typealias Swift Export surface the way an exported `object` does. iOS therefore
 * keeps a hand-written mirror, `let minSearchQueryLength`, in `SearchModels.swift`.
 *
 * A hand-written mirror is exactly what caused the bug this constant exists to fix: the floor was
 * copied into every caller, the FTS tokenizer moved from `porter` to `trigram`, and not one copy
 * followed — so a two-character search ran against an index that could never answer it and the UI
 * reported "no results". The Kotlin copies are gone; this test is what stops the remaining Swift one
 * from drifting the same way, since no compiler can check across the language boundary.
 *
 * If Swift Export ever does expose the constant directly, delete the mirror and this test together.
 */
class SwiftSearchFloorMirrorTest :
    FunSpec({

        test("the Swift minSearchQueryLength mirror equals the Kotlin MIN_SEARCH_QUERY_LENGTH") {
            val searchModels =
                File(Konsist.projectRootPath, "app/iosApp/ListenUp/Features/Search/SearchModels.swift")
            check(searchModels.isFile) {
                "Expected the Swift mirror at ${searchModels.path}. If the file moved, update this " +
                    "test — do not delete it, or the mirror silently stops being checked."
            }

            val mirrored =
                MIRROR_DECLARATION
                    .find(searchModels.readText())
                    ?.groupValues
                    ?.get(1)
                    ?.toInt()

            // A null here means the declaration was renamed or reshaped, which would make a plain
            // equality check pass vacuously against nothing at all.
            check(mirrored != null) {
                "No `let minSearchQueryLength = <int>` declaration found in ${searchModels.name}. " +
                    "The mirror was renamed or removed; this gate cannot verify what it cannot find."
            }

            mirrored shouldBe MIN_SEARCH_QUERY_LENGTH
        }
    })

private val MIRROR_DECLARATION = Regex("""\blet\s+minSearchQueryLength\s*(?::\s*Int\s*)?=\s*(\d+)""")
