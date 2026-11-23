package com.calypsan.listenup.server.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import java.io.File

/**
 * Konsist guard pinning the StatsRecorder design's load-bearing invariant: `UserStatsRepository
 * .upsert(...)` and `BookReadsRepository.recordRead(...)` — the two stats-**source** write
 * primitives — are reachable only from the allowlisted classes below. Any other write path
 * naturally reaches for a cheap, mis-ordered cascade and recreates the "all-time works, windowed
 * fails" bug class StatsRecorder exists to kill (see
 * `docs/superpowers/specs/2026-06-30-statsrecorder-design.md`).
 *
 * **Structural check and its limitation.** Konsist 0.17.3 cannot resolve an arbitrary variable's
 * declared type from a bare `.upsert(`/`.recordRead(` call site, so this rule combines two
 * syntactic signals: the class must declare a primary-constructor parameter of the restricted
 * type, AND some function body (comments stripped) must contain `<paramName>.upsert(` /
 * `<paramName>.recordRead(`, where `<paramName>` is that exact constructor parameter's name. This
 * is exact for the current codebase (verified by hand against every existing holder of these two
 * types) but degrades like any name/structure heuristic: a class that legitimately holds the type
 * for *another* reason and happens to also call `.upsert(`/`.recordRead(` on a *different*
 * same-NAMED parameter (i.e. another constructor parameter that happens to share this one's name)
 * would false-positive. None do today.
 *
 * Deviation from the original design: anchoring the call token to the holder parameter's own name
 * (rather than a bare `.upsert(`/`.recordRead(` anywhere in the class) is required because
 * [com.calypsan.listenup.server.api.PlaybackServiceImpl] holds a `UserStatsRepository` (for reads,
 * via `.getForUser(`) AND, in an unrelated function, calls `listeningEventRepository.upsert(...)`
 * — a same-named method on a *different* repository. A bare-token match would misreport that as a
 * `UserStatsRepository.upsert(` call. Anchoring to `userStatsRepository.upsert(` (the exact
 * receiver) resolves it without widening the allowlist.
 *
 * **Known blind spot (false NEGATIVE — this rule can miss a real violation).** The scan is
 * class-scoped and constructor-param-anchored: it only inspects a class's primary-constructor
 * parameters and that class's own function bodies. It does NOT see (a) a top-level function (e.g.
 * `fun Route.xxxRoutes()`) that writes via a repository obtained through `get()` / a route
 * parameter rather than a constructor param, or (b) a repository obtained any other way than a
 * primary-constructor parameter (a property, a function parameter, a Koin `get()` call inline).
 * Either shape reaches `.upsert(`/`.recordRead(` on the real repository with zero classes involved,
 * so this rule reports no offenders and stays green while the invariant it exists to protect is
 * broken. Treat "the rule is green" as "no *constructor-held, class-scoped* violation exists" —
 * not as a blanket guarantee that no 5th recurrence of the bug class is possible.
 *
 * **Raw table writes are censused separately.** A class can also write `book_reads` / `user_stats`
 * straight through SQLDelight (`sql.bookReadsQueries.<write>(…)`, or an alias of it), holding no
 * repository at all. The second test reads the write labels from `BookReads.sq` / `UserStats.sq`
 * (every label whose statement is not a `SELECT`) and requires every production file that calls one to
 * be in [RAW_STATS_TABLE_WRITERS], each with its reason; an entry that no longer writes fails.
 */
class StatsRecorderIsSoleStatsWriterRule :
    FunSpec({
        test(
            "UserStatsRepository.upsert and BookReadsRepository.recordRead are reachable only from the allowlisted classes",
        ) {
            // Narrowed to the `server` module's production sources — the two stats-write primitives and
            // every holder of them live in `:server`. A whole-repo `scopeFromProduction()` parsed every
            // module's PSI (the dominant cost of the server suite) for zero added coverage. Production
            // scope still excludes the jvmTest rogue fixtures the SelfTest points at directly.
            val scope = Konsist.scopeFromProduction("server")
            // Vacuity guard: prove the narrowed scope actually reached server production by asserting
            // the choke-point class is present. Without it, a misconfigured scope finds zero holders
            // and the rule passes while the invariant is unguarded.
            require(scope.classes().any { it.name == "StatsRecorder" }) {
                "StatsRecorderIsSoleStatsWriterRule found no StatsRecorder in the `server` scope — " +
                    "the scope is misconfigured and the rule would pass vacuously"
            }
            findOffenders(scope).shouldBeEmpty()
        }

        test("direct SQLDelight writes to book_reads and user_stats come only from the named files") {
            val labels = statsWriteLabels()
            withClue("write labels read from the .sq files: $labels") {
                labels.size shouldBeGreaterThanOrEqual 10
                labels shouldContainAll
                    setOf("insert", "update", "softDeleteById", "insertPulled", "deletePulledForUser")
            }
            val writers =
                Konsist
                    .scopeFromProduction("server")
                    .files
                    .filter { directStatsWrites(it.text, labels).isNotEmpty() }
                    .map { it.path.substringAfterLast("/") }
                    .toSet()
            val offenders = writers - RAW_STATS_TABLE_WRITERS.keys
            withClue("Files writing the stats tables without the recorder: $offenders") { offenders.shouldBeEmpty() }
            val stale = RAW_STATS_TABLE_WRITERS.keys - writers
            withClue("Allowlisted files that no longer write the stats tables: $stale") { stale.shouldBeEmpty() }
        }
    }) {
    companion object {
        /**
         * Holders of [com.calypsan.listenup.server.services.UserStatsRepository] allowed to call
         * `.upsert(`: the ordered choke-point itself, the idempotent full-rebuild it calls for
         * [com.calypsan.listenup.server.services.StatsEvent.BulkRecompute], and the lazy window-decay
         * self-heal `UserStatsRepository.pullSince` triggers (also an idempotent re-derive).
         */
        val USER_STATS_WRITE_ALLOWLIST = setOf("StatsRecorder", "UserStatsBackfillService", "UserStatsUpdater")

        /**
         * Holders of [com.calypsan.listenup.server.services.BookReadsRepository] allowed to call its
         * write methods — the low-level `.recordRead(` and the Spec-004 coverage-rule entry point
         * `.recordCompletion(`. Both are stats-source writes and must stay behind the ordered
         * choke-point. (The rebuild reconcile writes `book_reads` via the top-level
         * `reconcileBookReadsFromPositions`, not a repository method, so it isn't matched here.)
         */
        val BOOK_READS_WRITE_ALLOWLIST = setOf("StatsRecorder")

        /**
         * Production files allowed to call a `book_reads` / `user_stats` write statement directly, by file
         * name, each with its reason. Anything else goes through the repositories and [StatsRecorder].
         */
        val RAW_STATS_TABLE_WRITERS =
            mapOf(
                "BookReadsRepository.kt" to "the book_reads repository itself — the primitive the recorder calls",
                "UserStatsRepository.kt" to "the user_stats repository itself — the primitive the recorder calls",
                "UserStatsDerivation.kt" to
                    "the rebuild's book_reads reconcile from finished positions, inside the recorder's bulk recompute",
                "HardcoverPullStore.kt" to
                    "mirrors Hardcover's own reads (rows keyed hardcover:…); pulled reads are not ListenUp listening",
                "HardcoverConnectionStore.kt" to
                    "forgets the pulled Hardcover reads when the account is disconnected — the mirror's own lifecycle",
            )

        private val SQ_FILES = listOf("BookReads.sq", "UserStats.sq")

        private val LABEL = Regex("""^(\w+):\s*$""", RegexOption.MULTILINE)

        /** Every label in the stats tables' `.sq` files whose statement does not start with `SELECT`. */
        fun statsWriteLabels(): Set<String> =
            File(Konsist.projectRootPath, "server/src/commonMain/sqldelight")
                .walkTopDown()
                .filter { it.name in SQ_FILES }
                .toList()
                .also { check(it.size == SQ_FILES.size) { "expected $SQ_FILES, found ${it.map(File::getName)}" } }
                .flatMap { writeLabelsIn(it.readText()) }
                .toSet()

        /** The labels in one `.sq` [source] whose statement — the text up to the next label — is not a `SELECT`. */
        fun writeLabelsIn(source: String): List<String> {
            val code = source.lines().joinToString("\n") { it.substringBefore("--") }
            val labels = LABEL.findAll(code).toList()
            return labels.mapIndexedNotNull { index, label ->
                val end = labels.getOrNull(index + 1)?.range?.first ?: code.length
                val statement = code.substring(label.range.last + 1, end).trim()
                label.groupValues[1].takeUnless { statement.startsWith("SELECT", ignoreCase = true) }
            }
        }

        /**
         * The write labels [source] calls on `bookReadsQueries` / `userStatsQueries`, directly or through a
         * local alias (`val reads = sql.bookReadsQueries`), comments stripped.
         */
        fun directStatsWrites(
            source: String,
            writeLabels: Set<String>,
        ): List<String> {
            val code = stripComments(source)
            val aliases =
                Regex("""\b(?:val|var)\s+(\w+)\s*=\s*[\w.]*\b(?:bookReads|userStats)Queries\b""")
                    .findAll(code)
                    .map { it.groupValues[1] }
                    .toList()
            val receivers = listOf("bookReadsQueries", "userStatsQueries") + aliases
            return writeLabels.filter { label ->
                receivers.any { receiver -> Regex("""\b$receiver\s*\.\s*$label\s*\(""").containsMatchIn(code) }
            }
        }

        fun findOffenders(scope: KoScope): List<String> =
            violatingClasses(
                scope,
                holderType = "UserStatsRepository",
                callToken = ".upsert(",
                allowlist = USER_STATS_WRITE_ALLOWLIST,
            ) +
                violatingClasses(
                    scope,
                    holderType = "BookReadsRepository",
                    callToken = ".recordRead(",
                    allowlist = BOOK_READS_WRITE_ALLOWLIST,
                ) +
                violatingClasses(
                    scope,
                    holderType = "BookReadsRepository",
                    callToken = ".recordCompletion(",
                    allowlist = BOOK_READS_WRITE_ALLOWLIST,
                )

        private fun violatingClasses(
            scope: KoScope,
            holderType: String,
            callToken: String,
            allowlist: Set<String>,
        ): List<String> =
            scope
                .classes()
                .filterNot { it.name in allowlist }
                .mapNotNull { cls ->
                    val holderParamName =
                        cls.primaryConstructor
                            ?.parameters
                            ?.firstOrNull { it.type.name == holderType }
                            ?.name
                            ?: return@mapNotNull null
                    val qualifiedCallToken = "$holderParamName$callToken"
                    val calls = cls.functions().any { stripComments(it.text).contains(qualifiedCallToken) }
                    if (!calls) return@mapNotNull null
                    "${cls.name} holds a $holderType and calls $qualifiedCallToken — only $allowlist may @ ${cls.path}"
                }
    }
}
