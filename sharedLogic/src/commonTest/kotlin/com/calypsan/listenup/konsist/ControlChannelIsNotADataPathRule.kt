package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Structural guard (Plan §6(c)): the SSE control channel accelerates convergence — it is
 * **never** the sole path a piece of persisted state travels. Persisted state goes through
 * `SqlSyncableRepository` (revision-bumped, replayable, catch-up/digest-recoverable); a
 * control frame only tells clients to reconcile *sooner*. Three ways that invariant erodes,
 * three sub-rules:
 *
 *  1. **A new `broadcastControl` / `publishControl` call site.** Every control emission lives
 *     in an explicit `(file, frame)` allowlist — the §2 census. A new call site is a new
 *     place state might ride a lossy frame; it fails until it is reviewed onto the list. The
 *     census is asserted in BOTH directions: an allowlist entry whose emission has since been
 *     deleted also fails, so a cleared slot can't silently outlive its call site and wave a
 *     future emission through unreviewed.
 *  2. **A firehose-suppressed bulk write that forgets its accelerator.** Any path entering
 *     `withContext(FirehoseSuppressed)` writes rows *above* the client cursor that never hit
 *     the live tail; it MUST broadcast exactly one `SyncControl.LibraryDataChanged` so other
 *     clients reconcile live instead of only on their next reconnect (§5's pairing rule).
 *  3. **A resurrected lossy nudge.** The retired `MutableSharedFlow<Unit>` refresh-signal
 *     pattern must not reappear in the `data/repository` or `presentation` layers — the
 *     catalog's `RefreshedDomain` is the only sanctioned nudge shape.
 *
 * The detection logic is pure ([ControlChannelDetector]) so a fixture test can prove each
 * sub-rule fires on a synthetic violation.
 */
class ControlChannelIsNotADataPathRule :
    FunSpec({

        fun serverCommonMainFiles() =
            productionScope()
                .files
                .filter { it.path.contains("/server/") && it.path.contains("/commonMain/") }

        test("every broadcastControl/publishControl call site is on the (file, frame) allowlist") {
            val offenders =
                serverCommonMainFiles().flatMap { file ->
                    ControlChannelDetector.controlCallSiteOffenders(
                        fileName = file.path.substringAfterLast('/'),
                        source = file.text,
                        allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                    )
                }

            // Message on failure points the author at the plan; kept in the offender strings.
            offenders.shouldBeEmpty()
        }

        test("every CONTROL_CALL_SITES entry corresponds to a real emission (no rotted census entries)") {
            // The census is exact in both directions: a (file, frame) that no longer emits its frame
            // is a rotted allowlist slot — remove it, or a future emission slips through unreviewed.
            val sourcesByFileName =
                serverCommonMainFiles()
                    .groupBy { it.path.substringAfterLast('/') }
                    .mapValues { (_, files) -> files.joinToString("\n") { it.text } }

            val offenders =
                ControlChannelDetector.unusedAllowlistEntries(
                    sourcesByFileName = sourcesByFileName,
                    allowlist = ControlChannelDetector.CONTROL_CALL_SITES,
                )

            offenders.shouldBeEmpty()
        }

        test("every FirehoseSuppressed bulk-write path broadcasts SyncControl.LibraryDataChanged") {
            val offenders =
                serverCommonMainFiles()
                    .filter { ControlChannelDetector.firehoseSuppressedPairingViolation(it.text) }
                    .map {
                        "${it.name} enters withContext(FirehoseSuppressed) but never references " +
                            "SyncControl.LibraryDataChanged — a suppressed bulk write must broadcast the " +
                            "accelerator (docs/sync-core-centralization-plan.md §5)"
                    }

            offenders.shouldBeEmpty()
        }

        test("no MutableSharedFlow<Unit> refresh signals in data/repository or presentation") {
            val offenders =
                productionScope()
                    .files
                    .filter { ControlChannelDetector.isBannedRefreshSignal(it.path, it.text) }
                    .map {
                        "${it.name} declares a MutableSharedFlow<Unit> refresh signal — the retired lossy-nudge " +
                            "pattern. Use a catalog RefreshedDomain (docs/sync-core-centralization-plan.md §6c)."
                    }

            offenders.shouldBeEmpty()
        }
    })

/**
 * Pure detection for [ControlChannelIsNotADataPathRule], factored out so the fixture test can
 * feed it synthetic violations and assert it reports them.
 */
internal object ControlChannelDetector {
    /**
     * The complete census of control-frame emission sites, keyed by source-file name to the set of
     * `SyncControl` frames that file is allowed to emit. Adding a control emission means adding its
     * `(file, frame)` here — a deliberate review gate, per Plan §2/§6.
     */
    val CONTROL_CALL_SITES: Map<String, Set<String>> =
        mapOf(
            "ImportApplier.kt" to setOf("LibraryDataChanged"),
            "BookPersister.kt" to setOf("LibraryDataChanged"),
            "RestoreOrchestrator.kt" to setOf("LibraryDataChanged"),
            "AdminSettingsServiceImpl.kt" to setOf("ServerInfoChanged"),
            "UserPreferencesServiceImpl.kt" to setOf("PreferencesChanged"),
            "CollectionServiceImpl.kt" to setOf("AccessChanged"),
            "AdminUserServiceImpl.kt" to setOf("UserDeleted"),
            "ActiveSessionCleanupTask.kt" to setOf("ActiveSessionsChanged"),
            "ActiveSessionRepository.kt" to setOf("ActiveSessionsChanged"),
            // Campfire rooms are ephemeral (never persisted, never revisioned) — the discovery
            // nudge is pure accelerator by construction; clients re-list open sessions via RPC.
            "CampfireServiceImpl.kt" to setOf("CampfiresChanged"),
            "CampfireReaperTask.kt" to setOf("CampfiresChanged"),
        )

    /**
     * Offending control emissions in one file: any `broadcastControl(SyncControl.X)` /
     * `publishControl(SyncControl.X, …)` whose `(fileName, X)` pair is not on [allowlist].
     * Comments are stripped first so KDoc samples never false-trigger.
     */
    fun controlCallSiteOffenders(
        fileName: String,
        source: String,
        allowlist: Map<String, Set<String>>,
    ): List<String> {
        val allowed = allowlist[fileName].orEmpty()
        return CONTROL_CALL_REGEX
            .findAll(stripComments(source))
            .map { it.groupValues[1] }
            .filter { frame -> frame !in allowed }
            .map { frame ->
                "$fileName emits SyncControl.$frame via a control frame that is not on the " +
                    "(file, frame) allowlist — persisted state goes through SqlSyncableRepository; control " +
                    "frames only accelerate. See docs/sync-core-centralization-plan.md §6c."
            }.toList()
    }

    /**
     * The reverse of [controlCallSiteOffenders]: allowlist entries with no backing emission — a
     * rotted census entry. For each `(fileName, frame)` on [allowlist], the file's real source in
     * [sourcesByFileName] must actually emit `broadcastControl/publishControl(SyncControl.frame)`;
     * an entry whose file is absent or emits a different (or no) frame is reported. Asserting the
     * census in this direction too means a control emission that is *deleted* can't leave a
     * permanently-cleared allowlist slot behind for a future emission to slip through unreviewed.
     */
    fun unusedAllowlistEntries(
        sourcesByFileName: Map<String, String>,
        allowlist: Map<String, Set<String>>,
    ): List<String> =
        allowlist.flatMap { (fileName, frames) ->
            val emitted =
                sourcesByFileName[fileName]
                    ?.let { source ->
                        CONTROL_CALL_REGEX.findAll(stripComments(source)).map { it.groupValues[1] }.toSet()
                    }.orEmpty()
            frames.filter { it !in emitted }.map { frame ->
                "$fileName is allowlisted to emit SyncControl.$frame but no such control frame is emitted " +
                    "there — a rotted census entry. Remove it from CONTROL_CALL_SITES. See " +
                    "docs/sync-core-centralization-plan.md §2."
            }
        }

    /**
     * True when [source] enters a firehose-suppressed write context but never names
     * `SyncControl.LibraryDataChanged` — a suppressed bulk write with no accelerator (§5).
     * Scoped to the `withContext(FirehoseSuppressed` gate-*setter* (the write path), not every
     * mention of the type, so the substrate that merely *reads* the gate is not implicated.
     */
    fun firehoseSuppressedPairingViolation(source: String): Boolean {
        val stripped = stripComments(source)
        val entersSuppressedWrite = stripped.contains("withContext(FirehoseSuppressed")
        val broadcastsAccelerator = stripped.contains("LibraryDataChanged")
        return entersSuppressedWrite && !broadcastsAccelerator
    }

    /** True when [path] is a client repository/presentation file declaring a `MutableSharedFlow<Unit>`. */
    fun isBannedRefreshSignal(
        path: String,
        source: String,
    ): Boolean {
        val inBannedLayer =
            path.contains("/data/repository/") || path.contains("/presentation/")
        return inBannedLayer && stripComments(source).contains("MutableSharedFlow<Unit>")
    }

    // A control emission: `broadcastControl(` or `publishControl(` whose first argument is a
    // `SyncControl.<Frame>` (whitespace/newline tolerant — some call sites break the line after `(`).
    private val CONTROL_CALL_REGEX =
        Regex("""(?:broadcastControl|publishControl)\s*\(\s*SyncControl\.(\w+)""")
}

private fun stripComments(source: String): String =
    source
        .replace(Regex("""//[^\n]*"""), "")
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
