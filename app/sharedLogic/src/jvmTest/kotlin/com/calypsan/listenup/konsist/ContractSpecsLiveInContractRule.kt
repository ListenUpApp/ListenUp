package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * Specs for the wire contract live in `:contract`, not `:app:sharedLogic`.
 *
 * `:contract` runs its `commonTest` on Kotlin/Native, the target the released server ships on.
 * A test file declaring a contract package is therefore in the wrong module: it would only run on the
 * JVM and Android host, never where the wire types ship.
 *
 * Konsist's production scope excludes tests, so this reads the raw files: every `.kt` file under a
 * test source set of `:app:sharedLogic` is checked for its `package` line.
 */
class ContractSpecsLiveInContractRule :
    FunSpec({

        test("no :app:sharedLogic test file declares a com.calypsan.listenup.api package") {
            val srcDir = File(Konsist.projectRootPath, "app/sharedLogic/src")
            val testSources =
                srcDir
                    .listFiles { entry -> entry.isDirectory && "test" in entry.name.lowercase() }
                    .orEmpty()
                    .flatMap { sourceSet -> sourceSet.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

            assertScopeNotEmpty(
                testSources,
                expectedMin = 500,
                why = "the Kotlin test files under :app:sharedLogic/src/*Test* — a renamed source set would silently empty this",
            )

            val offenders =
                testSources
                    .filter { file -> file.declaresContractPackage() }
                    .map { it.relativeTo(srcDir).path }

            offenders.shouldBeEmpty()
        }
    })

private val packageLine = Regex("""^package\s+(com\.calypsan\.listenup\.api(?:\.[\w.]+)?)\s*$""", RegexOption.MULTILINE)

private fun File.declaresContractPackage(): Boolean = packageLine.containsMatchIn(readText())
