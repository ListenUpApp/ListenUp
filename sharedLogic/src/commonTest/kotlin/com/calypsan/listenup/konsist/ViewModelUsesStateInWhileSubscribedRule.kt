package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Every `*ViewModel` in `client/presentation/` that exposes a public
 * `StateFlow<*UiState>` must derive it via `.stateIn(... WhileSubscribed ...)`.
 * Pins the canonical reactive-state pattern from Playback P3.
 *
 * Implementation note: Konsist can't easily AST-match the operator chain
 * across multiple lines, so this rule does a text-level check on the file
 * body — if the file declares a `StateFlow<` referencing a UiState and the
 * file body does not contain the literal substring `WhileSubscribed`, the
 * rule fails. Documented limitation, acceptable trade-off vs the value of
 * pinning the convention.
 *
 * Legacy exclusions: the 28 ViewModels below were present before P3 and do not
 * yet use `WhileSubscribed`. They are tracked for future migration in
 * `docs/superpowers/followups.md` ("From Playback P3 — WhileSubscribed backlog").
 * Do NOT add P3-touched ViewModels to this list — fix the ViewModel instead.
 */
class ViewModelUsesStateInWhileSubscribedRule :
    FunSpec({
        // Legacy non-P3 ViewModels deliberately left without WhileSubscribed; tracked for
        // future migration. Each entry is the simple class name (without package).
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
                "ABSImportViewModel",
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
                "InviteRegistrationViewModel",
                "ClaimInviteViewModel",
                "ContributorEditViewModel",
                "SeriesEditViewModel",
                "ShelfDetailViewModel",
                "CreateEditShelfViewModel",
                "BookEditViewModel",
                "ServerConnectViewModel",
                "MetadataViewModel",
            )

        test("every UiState-exposing ViewModel uses stateIn(WhileSubscribed) (excluding legacy backlog)") {
            val offenders =
                Konsist
                    .scopeFromProduction()
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
