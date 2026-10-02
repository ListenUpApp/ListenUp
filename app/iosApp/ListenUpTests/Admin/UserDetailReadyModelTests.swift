import Testing
import Shared
@testable import ListenUp

/// Pins how the shared `UserDetailUiState.Ready` flattens into the native snapshot the SwiftUI screen
/// binds to — in particular that the Can Edit toggle reads the ViewModel's `canEdit`, whichever way it
/// is set. (The observer wraps a concrete Kotlin `UserDetailViewModel`, so there is no seam to pin that
/// the toggle calls `toggleCanEdit`; the mapping is what this screen owns.)
struct UserDetailReadyModelTests {
    private func ready(canEdit: Bool, isProtected: Bool = false, isSaving: Bool = false) -> UserDetailUiStateReady {
        UserDetailUiStateReady(
            user: AdminUserInfo(
                id: "u1",
                email: "kaladin@example.com",
                displayName: "Kaladin",
                firstName: nil,
                lastName: nil,
                isRoot: false,
                role: "member",
                status: "active",
                permissions: UserPermissions(canEdit: canEdit),
                createdAt: "2026-01-01"
            ),
            canEdit: canEdit,
            isProtected: isProtected,
            isSaving: isSaving,
            error: nil
        )
    }

    @Test func revokedCanEditMapsToAnOffToggle() {
        let model = UserDetailReadyModel(from: ready(canEdit: false))
        #expect(model.canEdit == false)
        #expect(model.displayName == "Kaladin")
        #expect(model.email == "kaladin@example.com")
        #expect(model.role == "member")
    }

    @Test func grantedCanEditMapsToAnOnToggle() {
        #expect(UserDetailReadyModel(from: ready(canEdit: true)).canEdit == true)
    }

    @Test func protectedAndSavingFlagsCarryAcross() {
        let model = UserDetailReadyModel(from: ready(canEdit: true, isProtected: true, isSaving: true))
        #expect(model.isProtected == true)
        #expect(model.isSaving == true)
        #expect(model.error == nil)
    }
}
