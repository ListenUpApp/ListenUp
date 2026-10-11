package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * An edit screen asks before it discards a draft, through the one guard, `rememberUnsavedWorkGuard`.
 *
 * It pairs the system Back handler, the hold an outside navigation respects and the shared
 * "Unsaved changes" dialog, so a screen cannot ask without holding or hold without asking. Before it,
 * four screens hand-rolled that trio with private copies of the dialog.
 *
 * Two halves:
 * (a) a `features/` file that reads a draft flag (`.hasChanges`, `.isDirty`, `isDraftChanged(`,
 *     `hasChanges(`) must call `rememberUnsavedWorkGuard(`, or be listed in [GUARD_EXEMPT] with a reason;
 * (b) no `features/` file calls `HoldUnsavedWork(` or declares its own unsaved-changes dialog, except
 *     those listed in [DIRECT_HOLD] with a reason.
 *
 * Both maps are keyed by file name. Comments are skipped.
 */
class EditScreensGuardUnsavedWorkRule :
    FunSpec({
        val featureFiles =
            productionScope()
                .files
                .filter { "/app/sharedUI/src/" in it.path && "/features/" in it.path }

        test("the features/ population is not empty") {
            assertScopeNotEmpty(
                featureFiles,
                expectedMin = 200,
                why = "every :app:sharedUI features/ production file — the screens whose drafts this pins",
            )
            assertScopeNotEmpty(
                featureFiles.filter { callsGuard(it.text) },
                expectedMin = 6,
                why = "the edit screens that already guard their draft — a rule that finds none is reading nothing",
            )
        }

        test("a file that reads a draft flag guards it") {
            val offenders =
                featureFiles
                    .filter { it.nameWithExtension !in GUARD_EXEMPT }
                    .filter { readsDraftFlag(it.text) && !callsGuard(it.text) }
                    .map { it.path }
            withClue("reads a draft flag without rememberUnsavedWorkGuard(:\n${offenders.joinToString("\n")}") {
                offenders.shouldBeEmpty()
            }
        }

        test("no feature file holds unsaved work or declares an unsaved-changes dialog by hand") {
            val offenders =
                featureFiles
                    .filter { it.nameWithExtension !in DIRECT_HOLD }
                    .filter { holdsOrAsksByHand(it.text) }
                    .map { it.path }
            withClue("holds or asks by hand:\n${offenders.joinToString("\n")}") { offenders.shouldBeEmpty() }
        }

        test("every exemption still names a file that needs it") {
            val byName = featureFiles.associateBy { it.nameWithExtension }
            GUARD_EXEMPT.keys
                .filter { name -> byName[name]?.let { readsDraftFlag(it.text) && !callsGuard(it.text) } != true }
                .shouldBeEmpty()
            DIRECT_HOLD.keys
                .filter { name -> byName[name]?.let { holdsOrAsksByHand(it.text) } != true }
                .shouldBeEmpty()
        }
    })

/** Files that read a draft flag without being the screen that guards it, file name to reason. */
internal val GUARD_EXEMPT: Map<String, String> = emptyMap()

/** Files that raise a hold or ask by hand, file name to reason. */
internal val DIRECT_HOLD: Map<String, String> =
    mapOf(
        "UploadBooksScreen.kt" to
            "holds an upload in flight (stop or finish), not an edit draft: its own stop-upload question, not Discard",
    )

private val DRAFT_FLAG = Regex("""\.hasChanges\b|\.isDirty\b|\bisDraftChanged\(|\bhasChanges\(""")
private val HOLD_CALL = Regex("""(?<!fun )\bHoldUnsavedWork\(""")
private val OWN_DIALOG = Regex("""\bfun\s+(?:Unsaved|Discard)\w*Dialog\(""")

internal fun readsDraftFlag(text: String): Boolean = DRAFT_FLAG.containsMatchIn(blankComments(text))

internal fun callsGuard(text: String): Boolean = "rememberUnsavedWorkGuard(" in blankComments(text)

internal fun holdsOrAsksByHand(text: String): Boolean {
    val code = blankComments(text)
    return HOLD_CALL.containsMatchIn(code) || OWN_DIALOG.containsMatchIn(code)
}
