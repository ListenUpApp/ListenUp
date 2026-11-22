package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every `*ViewModel` in `client/presentation/` derives each public `StateFlow<…UiState>` property via
 * `.stateIn(… WhileSubscribed …)`. Pins the canonical reactive-state pattern.
 *
 * The check is per property, not per file: a ViewModel that exposes one state through a
 * `MutableStateFlow` does not pass just because another flow in the same file uses
 * `WhileSubscribed`. A property's initializer is its text up to the next class-level declaration,
 * comments stripped; it passes when it calls `.stateIn(` and the last such call names `WhileSubscribed`
 * (see [uiStatePropertiesNotFromStateIn]).
 *
 * Exclusions fall into two kinds:
 *  - Legacy backlog: older ViewModels that do not yet use `WhileSubscribed`, awaiting
 *    migration. Do NOT add reactive ViewModels here — fix the ViewModel instead.
 *  - By-design: imperative command-pipeline ViewModels (the ABS, import, and backup admin flows)
 *    whose state is driven by user actions + progress events, not projected from an upstream
 *    flow. `stateIn(WhileSubscribed)` is the wrong tool there; a `MutableStateFlow` with
 *    imperative transitions is correct, so they are excluded deliberately.
 *
 * The list is kept honest from the other side too: an excluded ViewModel that would pass, or one that
 * no longer exists, fails the second test until its entry is removed.
 */
class ViewModelUsesStateInWhileSubscribedRule :
    FunSpec({
        // Legacy ViewModels deliberately left without WhileSubscribed, awaiting migration.
        // Each entry is the simple class name (without package).
        val legacyExclusions =
            setOf(
                "SettingsViewModel",
                "SetupViewModel",
                "LoginViewModel",
                "PendingApprovalViewModel",
                "RegisterViewModel",
                "LibrarySetupViewModel",
                "AdminBackupViewModel",
                "AdminCollectionDetailViewModel",
                "RestoreBackupViewModel",
                "ABSImportHubViewModel",
                "AdminViewModel",
                "AdminCollectionsViewModel",
                "CreateInviteViewModel",
                "AdminCategoriesViewModel",
                "LibrarySettingsViewModel",
                "AdminSettingsViewModel",
                "ClaimInviteViewModel",
                "ContributorEditViewModel",
                "SeriesEditViewModel",
                "ShelfDetailViewModel",
                "CreateEditShelfViewModel",
                "BookEditViewModel",
                "ServerConnectViewModel",
                // Found when the rule went per property: discoverShelvesState is an explicit-backing-field
                // MutableStateFlow that passed only because another flow in the file uses WhileSubscribed.
                "DiscoverViewModel",
                // Found when the rule went per property: addSubSeries re-exposes SubSeriesAdder.state, which is
                // itself stateIn(WhileSubscribedForUi) — a delegation the text check cannot follow.
                "SeriesDetailViewModel",
                // By-design (not migration debt): imperative command-pipeline VM
                // (upload → analyze → apply, driven by user actions + progress events), same
                // shape as the ABS*/AdminBackup admin VMs above. No upstream flow to project, so
                // stateIn(WhileSubscribed) doesn't apply — MutableStateFlow is the right pattern.
                "ImportFlowViewModel",
                // By-design: mirrors PendingApprovalViewModel's stream-plus-poll shape exactly —
                // state is driven by RPC push events, a poll fallback, and user actions
                // (requestReset/completeReset), not projected from a single upstream flow.
                "ForgotPasswordViewModel",
                // By-design: preferences are online RPC (load → optimistic toggle → revert on
                // refusal), driven by user actions with no upstream flow to project — the same
                // imperative shape as the admin command-pipeline VMs above.
                "NotificationPrefsViewModel",
                // By-design: same imperative command-pipeline shape (edit buffer -> preview ->
                // save-and-run, driven by user actions + run progress events).
                "OrganizeSettingsViewModel",
                // By-design: the same shape as ImportFlowViewModel above — a user-triggered
                // transfer (pick -> stage each file -> finalize) whose state is driven by upload
                // progress, not projected from an upstream flow.
                "UploadBooksViewModel",
            )

        test("every public StateFlow<…UiState> property comes from stateIn(WhileSubscribed), excluding the list") {
            val viewModels = presentationViewModels()

            assertScopeNotEmpty(
                viewModels,
                expectedMin = 30,
                why = "ViewModels under client/presentation/ — an empty set means the rule policed nothing",
            )

            val offenders =
                viewModels
                    .filterNot { it.name in legacyExclusions }
                    .flatMap { vm -> uiStatePropertiesNotFromStateIn(vm.text).map { "${vm.name}.$it" } }
            offenders shouldBe emptyList()
        }

        test("every excluded ViewModel exists and still needs its exclusion") {
            val failingByName =
                presentationViewModels().associate { it.name to uiStatePropertiesNotFromStateIn(it.text) }
            val stale = legacyExclusions.filter { failingByName[it].isNullOrEmpty() }
            withClue("these pass the rule or no longer exist — remove them from the exclusion list") {
                stale shouldBe emptyList()
            }
        }
    })

private fun presentationViewModels() =
    productionScope()
        .classes()
        .filter { it.path.contains("/sharedLogic/") }
        .filter { it.path.contains("/client/presentation/") }
        .filter { it.name.endsWith("ViewModel") }

/** A public class-level `val` typed `StateFlow<…UiState…>` (four-space member indent, no visibility modifier). */
private val UI_STATE_PROPERTY =
    Regex("""^ {4}(?:override |open |final )*val\s+(\w+)\s*:\s*StateFlow<[^=\n]*UiState\b[^=\n]*>""")

/** The start of the next class-level member: a line at member indent or less that opens with a word, `@` or `/`. */
private val NEXT_MEMBER = Regex("""^ {0,4}[A-Za-z@/]""")

/**
 * The names of the public `StateFlow<…UiState>` properties declared in [classText] whose initializer does
 * not end in `.stateIn(…WhileSubscribed…)` — an explicit `field = MutableStateFlow(…)`, an
 * `asStateFlow()` view, a getter, or a `stateIn` with another start policy.
 */
internal fun uiStatePropertiesNotFromStateIn(classText: String): List<String> {
    val lines = stripComments(classText).lines()
    return lines.indices.mapNotNull { index ->
        val name = UI_STATE_PROPERTY.find(lines[index])?.run { groupValues[1] } ?: return@mapNotNull null
        val end =
            (index + 1 until lines.size).firstOrNull { NEXT_MEMBER.containsMatchIn(lines[it]) } ?: lines.size
        val declaration = lines.subList(index, end).joinToString("\n")
        val lastStateIn = declaration.lastIndexOf(".stateIn(")
        name.takeUnless { lastStateIn >= 0 && "WhileSubscribed" in declaration.substring(lastStateIn) }
    }
}
