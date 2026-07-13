package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every `*ViewModel` in `client/presentation/` that exposes a public
 * `StateFlow<*UiState>` must derive it via `.stateIn(... WhileSubscribed ...)`.
 * Pins the canonical reactive-state pattern.
 *
 * Implementation note: Konsist can't easily AST-match the operator chain
 * across multiple lines, so this rule does a text-level check on the file
 * body — if the file declares a `StateFlow<` referencing a UiState and the
 * file body does not contain the literal substring `WhileSubscribed`, the
 * rule fails. Documented limitation, acceptable trade-off vs the value of
 * pinning the convention.
 *
 * Exclusions fall into two kinds:
 *  - Legacy backlog: older ViewModels that do not yet use `WhileSubscribed`, awaiting
 *    migration. Do NOT add reactive ViewModels here — fix the ViewModel instead.
 *  - By-design: imperative command-pipeline ViewModels (the ABS, import, and backup admin flows)
 *    whose state is driven by user actions + progress events, not projected from an upstream
 *    flow. `stateIn(WhileSubscribed)` is the wrong tool there; a `MutableStateFlow` with
 *    imperative transitions is correct, so they are excluded deliberately.
 */
class ViewModelUsesStateInWhileSubscribedRule :
    FunSpec({
        // Legacy ViewModels deliberately left without WhileSubscribed, awaiting migration.
        // Each entry is the simple class name (without package).
        val legacyExclusions =
            setOf(
                "SettingsViewModel",
                "ContributorMetadataViewModel",
                "SetupViewModel",
                "LoginViewModel",
                "PendingApprovalViewModel",
                "RegisterViewModel",
                "LibrarySetupViewModel",
                "UserDetailViewModel",
                "AdminBackupViewModel",
                "AdminCollectionDetailViewModel",
                "RestoreBackupViewModel",
                "ABSImportHubViewModel",
                "AdminViewModel",
                "AdminCollectionsViewModel",
                "CreateInviteViewModel",
                "AdminCategoriesViewModel",
                "AdminInboxViewModel",
                "LibrarySettingsViewModel",
                "AdminSettingsViewModel",
                "ClaimInviteViewModel",
                "ContributorEditViewModel",
                "SeriesEditViewModel",
                "ShelfDetailViewModel",
                "CreateEditShelfViewModel",
                "BookEditViewModel",
                "ServerConnectViewModel",
                "MetadataViewModel",
                // By-design (not migration debt): imperative command-pipeline VM
                // (upload → analyze → apply, driven by user actions + progress events), same
                // shape as the ABS*/AdminBackup admin VMs above. No upstream flow to project, so
                // stateIn(WhileSubscribed) doesn't apply — MutableStateFlow is the right pattern.
                "ImportFlowViewModel",
                // By-design: same imperative command-pipeline shape (edit buffer -> preview ->
                // save-and-run, driven by user actions + run progress events).
                "OrganizeSettingsViewModel",
                // By-design: chapter-editor draft VM — an in-memory draft mutated by user edits
                // (retime/add/remove/reparent) with an undo stack, only SEEDED from the repo flows
                // (not projected). stateIn(WhileSubscribed) can't model an imperatively-mutated
                // draft; MutableStateFlow with explicit transitions is correct.
                "ChapterEditorViewModel",
            )

        test("every UiState-exposing ViewModel uses stateIn(WhileSubscribed) (excluding legacy backlog)") {
            val offenders =
                productionScope()
                    .classes()
                    .filter { it.path.contains("/sharedLogic/") }
                    .filter { it.path.contains("/client/presentation/") }
                    .filter { it.name.endsWith("ViewModel") }
                    .filterNot { it.name in legacyExclusions }
                    .filter { vm -> vm.text.contains("StateFlow<") && vm.text.contains("UiState>") }
                    .filter { vm -> !vm.text.contains("WhileSubscribed") }
                    .map { it.name }
            offenders shouldBe emptyList()
        }
    })
