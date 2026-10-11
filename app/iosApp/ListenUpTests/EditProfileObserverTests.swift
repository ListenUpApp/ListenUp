import Foundation
import ListenupContract
import Shared
import Testing
@testable import ListenUp

/// Coverage for the Edit Profile screen: the avatar `Remove`-affordance decision, the Swift-side
/// `StagedAvatar` projection, and the `apply` flatten over the sealed `EditProfileUiState`.
@MainActor
@Suite("EditProfileAvatar")
struct EditProfileObserverTests {
    // MARK: - Remove affordance

    @Test func stagedUploadCanAlwaysBeRemoved() {
        // A freshly picked image can be backed out of, regardless of the stored avatar.
        #expect(EditProfileView.canRemoveAvatar(staged: .image(Data([0x01])), hasImageAvatar: false))
        #expect(EditProfileView.canRemoveAvatar(staged: .image(Data([0x01])), hasImageAvatar: true))
    }

    @Test func revertedAvatarHidesRemove() {
        // Already reverted to initials — nothing left to remove.
        #expect(!EditProfileView.canRemoveAvatar(staged: .reverted, hasImageAvatar: true))
        #expect(!EditProfileView.canRemoveAvatar(staged: .reverted, hasImageAvatar: false))
    }

    @Test func unstagedFollowsStoredAvatar() {
        // No pending change: Remove appears only when there's a real image avatar to clear.
        #expect(EditProfileView.canRemoveAvatar(staged: .none, hasImageAvatar: true))
        #expect(!EditProfileView.canRemoveAvatar(staged: .none, hasImageAvatar: false))
    }

    // MARK: - StagedAvatar projection

    @Test func stagedAvatarEquatesUploadsByImageBytes() {
        #expect(StagedAvatar.image(Data([0x01, 0x02])) == .image(Data([0x01, 0x02])))
        #expect(StagedAvatar.image(Data([0x01])) != .image(Data([0x02])))
    }

    @Test func stagedAvatarCasesAreDistinct() {
        #expect(StagedAvatar.none != .reverted)
        #expect(StagedAvatar.reverted != .image(Data([0x01])))
    }

    // MARK: - State mapping

    private func makeObserver() -> EditProfileObserver {
        EditProfileObserver(viewModel: Dependencies.shared.createEditProfileViewModel())
    }

    private func user(email: String) -> User {
        User(
            id: UserId(value: "user-1"),
            email: email,
            displayName: "Ada Lovelace",
            firstName: "Ada",
            lastName: "Lovelace",
            isAdmin: false,
            permissions: UserPermissions(
                canEditMetadata: false,
                canCurateLibrary: false,
                canContributeStoryWorld: false,
                canCurateStoryWorld: false,
                canMakeReadingOrders: false
            ),
            tagline: nil,
            createdAtMs: 1,
            updatedAtMs: 2
        )
    }

    /// Every string differs from every other, so a swapped pair of fields shows.
    private var editedReady: EditProfileUiStateReady {
        EditProfileUiStateReady(
            user: user(email: "ada@example.com"),
            firstName: "Augusta",
            lastName: "King",
            tagline: "Poetical science",
            currentPassword: "current-pw",
            newPassword: "new-pw",
            confirmPassword: "confirm-pw",
            avatarChange: AvatarChangeRevertToAuto.shared,
            hasImageAvatar: true,
            isDirty: true,
            isSaving: false
        )
    }

    private var savingReady: EditProfileUiStateReady {
        EditProfileUiStateReady(
            user: user(email: "countess@example.com"),
            firstName: "",
            lastName: "",
            tagline: "",
            currentPassword: "",
            newPassword: "",
            confirmPassword: "",
            avatarChange: AvatarChangeNone.shared,
            hasImageAvatar: false,
            isDirty: false,
            isSaving: true
        )
    }

    @Test func loadingKeepsTheObserverLoading() {
        let observer = makeObserver()
        observer.apply(EditProfileUiStateLoading.shared)
        #expect(observer.isLoading)
        #expect(observer.user == nil)
    }

    @Test func readyMapsEveryField() {
        let observer = makeObserver()
        observer.apply(editedReady)

        #expect(observer.isLoading == false)
        #expect(observer.user?.email == "ada@example.com")
        #expect(observer.firstName == "Augusta")
        #expect(observer.lastName == "King")
        #expect(observer.tagline == "Poetical science")
        #expect(observer.currentPassword == "current-pw")
        #expect(observer.newPassword == "new-pw")
        #expect(observer.confirmPassword == "confirm-pw")
        #expect(observer.isDirty)
        #expect(observer.isSaving == false)
        #expect(observer.hasImageAvatar)
        #expect(observer.stagedAvatar == .reverted)
    }

    @Test func aSecondReadyReplacesEveryField() {
        let observer = makeObserver()
        observer.apply(editedReady)
        observer.apply(savingReady)

        #expect(observer.isLoading == false)
        #expect(observer.user?.email == "countess@example.com")
        #expect(observer.firstName.isEmpty)
        #expect(observer.lastName.isEmpty)
        #expect(observer.tagline.isEmpty)
        #expect(observer.currentPassword.isEmpty)
        #expect(observer.newPassword.isEmpty)
        #expect(observer.confirmPassword.isEmpty)
        #expect(observer.isDirty == false)
        #expect(observer.isSaving)
        #expect(observer.hasImageAvatar == false)
        #expect(observer.stagedAvatar == .none)
    }

    @Test func errorStopsLoadingAndDropsTheUser() {
        let observer = makeObserver()
        observer.apply(editedReady)
        observer.apply(EditProfileUiStateError(message: "Could not load your profile."))

        #expect(observer.isLoading == false)
        #expect(observer.user == nil)
    }
}
