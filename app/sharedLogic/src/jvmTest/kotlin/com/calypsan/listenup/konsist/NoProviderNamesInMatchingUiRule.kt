package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Konsist guard: matching UI code never names a metadata provider.
 *
 * Match details shows every provider through the `MetadataSource.label` the server sends — "found in"
 * chips, source switches, cover tiles, the partial-failure banner. That is what lets a new provider arrive
 * with no new screens. A literal "Audible" in a screen is the first step back to the Audible-only app this
 * redesign retired, so copy takes the source label as an argument instead.
 *
 * Polices the matching feature packages (`features/match/`) of `:app:sharedUI` and `:app:webApp`, plus the
 * shared presentation package. Comments are stripped, so KDoc may still explain behaviour by example.
 */
class NoProviderNamesInMatchingUiRule :
    FunSpec({
        val providerNames = listOf("Audible", "Hardcover", "iTunes", "Audnexus")

        test("matching UI and presentation code take provider names from MetadataSource.label") {
            val files =
                productionScope()
                    .files
                    .filter { file ->
                        val path = file.path
                        ("/features/match/" in path && ("/sharedUI/" in path || "/webApp/" in path)) ||
                            "/client/presentation/match/" in path
                    }

            assertScopeNotEmpty(
                files,
                expectedMin = 6,
                why = "matching UI and presentation files — an empty set means the rule polices nothing",
            )

            val offenders =
                files.flatMap { file ->
                    val code = file.text.withoutCommentsForProviderRule()
                    providerNames
                        .filter { name -> Regex("\"[^\"\\n]*\\b$name\\b[^\"\\n]*\"").containsMatchIn(code) }
                        .map { "${file.path}: \"$it\"" }
                }
            withClue(
                "Provider names in matching UI code — use MetadataSource.label instead:\n${offenders.joinToString(
                    "\n",
                )}",
            ) {
                offenders shouldBe emptyList()
            }
        }
    })

private fun String.withoutCommentsForProviderRule(): String =
    replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("""//[^\n]*"""), " ")
