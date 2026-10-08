import Testing
import Shared
@testable import ListenUp

/// Pins how the shared `UserPermissionsUiState.Ready` flattens into the native model the SwiftUI screen
/// binds to: the role, the preset reading, the grouped toggles, the curate warning and the save state.
struct UserPermissionsReadyModelTests {
    private func ready(
        role: UserRole = .member,
        flags: UserPermissions = UserPermissions(
            canEditMetadata: true,
            canCurateLibrary: false,
            canContributeStoryWorld: true,
            canCurateStoryWorld: false
        ),
        preset: PermissionPreset = .contributor,
        presetsShown: Bool = true,
        curateWarning: Bool = false,
        changeCount: Int32 = 0,
        isRoot: Bool = false,
        extraSections: [PermissionSection] = []
    ) -> UserPermissionsUiStateReady {
        UserPermissionsUiStateReady(
            user: AdminUserInfo(
                id: "u1",
                email: "quinn@example.com",
                displayName: "Quinn",
                firstName: nil,
                lastName: nil,
                isRoot: isRoot,
                role: "MEMBER",
                status: "ACTIVE",
                permissions: flags,
                createdAt: "0",
                access: .contributor
            ),
            role: role,
            flags: flags,
            sections: [
                PermissionSection(
                    group: .library,
                    rows: [
                        PermissionRow(permission: .editMetadata, granted: flags.canEditMetadata, isUnsaved: false),
                        PermissionRow(
                            permission: .curateLibrary,
                            granted: flags.canCurateLibrary,
                            isUnsaved: curateWarning
                        )
                    ]
                )
            ] + extraSections,
            preset: preset,
            presetsShown: presetsShown,
            curateWarningShown: curateWarning,
            changeCount: changeCount,
            isProtected: isRoot,
            isConfirmingAdminPromotion: false,
            isSaving: false,
            error: nil
        )
    }

    @Test func aDefaultMemberMapsToContributorWithBothLibraryToggles() {
        let model = UserPermissionsReadyModel(from: ready())
        #expect(model.name == "Quinn")
        #expect(model.isAdmin == false)
        #expect(model.preset == .contributor)
        #expect(model.presetsShown == true)
        #expect(model.sections.count == 1)
        #expect(model.sections[0].group == .library)
        #expect(model.sections[0].rows.map(\.permission) == [.editMetadata, .curateLibrary])
        #expect(model.sections[0].rows.map(\.granted) == [true, false])
        #expect(model.hasChanges == false)
    }

    @Test func aStoryWorldSectionKeepsItsGroupAndRowOrder() {
        let storyWorld = PermissionSection(
            group: .storyWorld,
            rows: [
                PermissionRow(permission: .contributeStoryWorld, granted: true, isUnsaved: false),
                PermissionRow(permission: .curateStoryWorld, granted: false, isUnsaved: false)
            ]
        )
        let model = UserPermissionsReadyModel(from: ready(extraSections: [storyWorld]))
        #expect(model.sections.map(\.group) == [.library, .storyWorld])
        #expect(model.sections[1].rows.map(\.permission) == [.contributeStoryWorld, .curateStoryWorld])
        #expect(PermissionLabels.title(PermissionGroup.storyWorld) == "Story World")
        #expect(PermissionLabels.title(Permission.contributeStoryWorld) == "Contribute")
        #expect(PermissionLabels.title(Permission.curateStoryWorld) == "Curate")
    }

    @Test func anUnsavedCurateGrantCarriesTheWarningAndTheCount() {
        let model = UserPermissionsReadyModel(
            from: ready(
                flags: UserPermissions(
                    canEditMetadata: true,
                    canCurateLibrary: true,
                    canContributeStoryWorld: true,
                    canCurateStoryWorld: false
                ),
                preset: .librarian,
                curateWarning: true,
                changeCount: 1
            )
        )
        #expect(model.curateWarningShown == true)
        #expect(model.changeCount == 1)
        #expect(model.hasChanges == true)
    }

    @Test func anAdminRoleMeansNoToggles() {
        #expect(UserPermissionsReadyModel(from: ready(role: .admin)).isAdmin == true)
    }

    @Test func anOlderServerHidesPresets() {
        #expect(UserPermissionsReadyModel(from: ready(presetsShown: false)).presetsShown == false)
    }

    @Test func theOwnerIsProtected() {
        let model = UserPermissionsReadyModel(from: ready(isRoot: true))
        #expect(model.isOwner == true)
        #expect(model.isProtected == true)
    }

    @Test func onlyTheThreeRealPresetsArePickable() {
        #expect(UserPermissionsReadyModel.pickablePresets == [.listener, .contributor, .librarian])
    }
}
