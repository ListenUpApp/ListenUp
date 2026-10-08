import Testing
import Shared
@testable import ListenUp

/// Pins how the shared `UserDetailUiState.Ready` flattens into the native snapshot the SwiftUI screen
/// binds to.
struct UserDetailReadyModelTests {
    private func ready(isRoot: Bool = false) -> UserDetailUiStateReady {
        UserDetailUiStateReady(
            user: AdminUserInfo(
                id: "u1",
                email: "kaladin@example.com",
                displayName: "Kaladin",
                firstName: nil,
                lastName: nil,
                isRoot: isRoot,
                role: "member",
                status: "active",
                permissions: UserPermissions(
                    canEditMetadata: true,
                    canCurateLibrary: false,
                    canContributeStoryWorld: true,
                    canCurateStoryWorld: false
                ),
                createdAt: "2026-01-01",
                access: isRoot ? .owner : .contributor
            )
        )
    }

    @Test func identityFieldsCarryAcross() {
        let model = UserDetailReadyModel(from: ready())
        #expect(model.displayName == "Kaladin")
        #expect(model.email == "kaladin@example.com")
        #expect(model.userId == "u1")
        #expect(model.isProtected == false)
    }

    @Test func accessLabelCarriesAcross() {
        #expect(UserDetailReadyModel(from: ready()).access == .contributor)
        #expect(UserDetailReadyModel(from: ready(isRoot: true)).access == .owner)
    }

    @Test func theOwnerIsProtected() {
        #expect(UserDetailReadyModel(from: ready(isRoot: true)).isProtected == true)
    }
}
