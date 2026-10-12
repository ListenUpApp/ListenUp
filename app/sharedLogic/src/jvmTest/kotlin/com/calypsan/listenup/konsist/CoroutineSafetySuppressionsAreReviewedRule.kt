package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private val coroutineSafetySuppression =
    Regex("""@Suppress\([^)]*"(SuspendFunSwallowedCancellation|InjectDispatcher|GlobalCoroutineUsage)"[^)]*\)""")

/** How many annotations in [source] suppress one of the three coroutine-safety detectors. */
internal fun coroutineSafetySuppressionCount(source: String): Int = coroutineSafetySuppression.findAll(source).count()

/**
 * True for a main (production) source set of a non-frozen module: the directory right under
 * `src/` does not contain `test`, and the path is not under desktop, which is frozen.
 */
internal fun isReviewedProductionPath(relativePath: String): Boolean {
    val sourceSet = relativePath.substringAfter("/src/").substringBefore('/')
    return "test" !in sourceSet.lowercase() && "/desktop" !in relativePath.lowercase()
}

/**
 * Production files that may overrule a coroutine-safety detector, with how many times each may.
 * Every entry was read and is correct; the reason sits beside it.
 */
private val reviewedSuppressions: Map<String, Int> =
    mapOf(
        // persists the pause under NonCancellable, then rethrows
        "app/sharedLogic/src/androidMain/kotlin/com/calypsan/listenup/client/download/DownloadWorker.kt" to 1,
        // catches Throwable to classify it; a caller's cancellation is re-raised untouched
        "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/data/remote/RpcProxyCache.kt" to 4,
        // inside NonCancellable; every throwable must roll back and reopen the pool
        "server/src/commonMain/kotlin/com/calypsan/listenup/server/backup/RestoreOrchestrator.kt" to 1,
        // kills the child process, then rethrows
        "server/src/commonMain/kotlin/com/calypsan/listenup/server/process/ProcessRunner.kt" to 1,
        // deletes the half-written import directory, then rethrows
        "server/src/commonMain/kotlin/com/calypsan/listenup/server/routes/ImportRoutes.kt" to 1,
        // discards the partial file, then rethrows
        "server/src/commonMain/kotlin/com/calypsan/listenup/server/routes/UploadRoutes.kt" to 1,
        // the DI root that provides the dispatcher
        "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/di/AppCoreModule.kt" to 1,
        // the DI root that chooses the player scope's dispatcher (main, like a viewModelScope)
        "app/sharedLogic/src/commonMain/kotlin/com/calypsan/listenup/client/di/PlayerModule.kt" to 1,
        // the actual dispatcher definitions
        "contract/src/androidMain/kotlin/com/calypsan/listenup/core/Dispatchers.android.kt" to 1,
        "contract/src/jvmMain/kotlin/com/calypsan/listenup/core/Dispatchers.jvm.kt" to 1,
        "server/src/commonMain/kotlin/com/calypsan/listenup/server/util/CpuDispatcher.kt" to 1,
        "server/src/jvmMain/kotlin/com/calypsan/listenup/server/db/sqldelight/IoDispatcher.jvm.kt" to 1,
        "server/src/jvmMain/kotlin/com/calypsan/listenup/server/io/IoDispatcher.jvm.kt" to 1,
    )

/**
 * Suppressing `SuspendFunSwallowedCancellation`, `InjectDispatcher` or `GlobalCoroutineUsage` in
 * production code is a decision, not a convenience: those detectors are what make the repo's
 * catch-all blocks and dispatcher use safe. A cancellation swallowed by a `catch (Throwable)` leaves
 * a coroutine running that its parent believes is gone.
 *
 * Every current suppression was read and is correct. This rule keeps it that way: a production file
 * not on [reviewedSuppressions], a count above its allowance, and an entry the file no longer earns
 * all fail. To add a suppression, put the file and count on the list with the reason beside it,
 * and have a reviewer agree with the reason. Test source sets may suppress these freely.
 */
class CoroutineSafetySuppressionsAreReviewedRule :
    FunSpec({
        test("coroutine-safety suppressions in production code are exactly the reviewed ones") {
            val production = walkKotlinSources().filter { isReviewedProductionPath(it.relativePath) }

            assertScopeNotEmpty(
                production,
                expectedMin = 2000,
                why = "production Kotlin files across the non-frozen modules",
            )

            val actual =
                production
                    .associate { it.relativePath to coroutineSafetySuppressionCount(it.text) }
                    .filterValues { it > 0 }

            val unreviewedOrOver =
                actual
                    .filter { (path, count) -> count > (reviewedSuppressions[path] ?: 0) }
                    .map { (path, count) -> "$path has $count, reviewed allowance ${reviewedSuppressions[path] ?: 0}" }
            val deadEntries =
                reviewedSuppressions
                    .filter { (path, allowed) -> (actual[path] ?: 0) < allowed }
                    .map { (path, allowed) -> "$path is reviewed for $allowed but has ${actual[path] ?: 0}" }

            val problems = unreviewedOrOver + deadEntries
            problems shouldBe emptyList()
        }
    })
