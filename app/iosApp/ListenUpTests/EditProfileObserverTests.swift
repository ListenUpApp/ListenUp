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
        #expect(EditProfileView.canRemoveAvatar(staged: .image(Data([0x01]), nil), hasImageAvatar: false))
        #expect(EditProfileView.canRemoveAvatar(staged: .image(Data([0x01]), nil), hasImageAvatar: true))
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

    @Test func aReframedAvatarCanStillBeRemoved() {
        #expect(EditProfileView.canRemoveAvatar(staged: .reframed(.centered), hasImageAvatar: true))
    }

    // MARK: - Adjust Photo

    @Test func adjustIsOfferedWheneverThereIsAnImageToFrame() {
        #expect(EditProfileView.canAdjustAvatar(staged: .image(Data([0x01]), nil), hasImageAvatar: false))
        #expect(EditProfileView.canAdjustAvatar(staged: .reframed(.centered), hasImageAvatar: true))
        #expect(EditProfileView.canAdjustAvatar(staged: .none, hasImageAvatar: true))
    }

    @Test func adjustIsNeverOfferedForInitials() {
        #expect(!EditProfileView.canAdjustAvatar(staged: .none, hasImageAvatar: false))
        #expect(!EditProfileView.canAdjustAvatar(staged: .reverted, hasImageAvatar: true))
    }

    @Test func adjustOpensAtTheFrameShownNow() {
        let stored = ImageFrameValue(focusX: 0.5, focusY: 0.3, zoom: 1.4)
        let staged = ImageFrameValue(focusX: 0.6, focusY: 0.4, zoom: 2.2)
        #expect(EditProfileView.currentAvatarFrame(staged: .none, stored: stored) == stored)
        #expect(EditProfileView.currentAvatarFrame(staged: .reframed(staged), stored: stored) == staged)
        #expect(EditProfileView.currentAvatarFrame(staged: .image(Data([0x01]), staged), stored: stored) == staged)
        // A new pick never wears the old photo's frame.
        #expect(EditProfileView.currentAvatarFrame(staged: .image(Data([0x01]), nil), stored: stored) == nil)
    }

    // MARK: - StagedAvatar projection

    @Test func stagedAvatarEquatesUploadsByImageBytesAndFrame() {
        #expect(StagedAvatar.image(Data([0x01, 0x02]), nil) == .image(Data([0x01, 0x02]), nil))
        #expect(StagedAvatar.image(Data([0x01]), nil) != .image(Data([0x02]), nil))
        #expect(StagedAvatar.image(Data([0x01]), nil) != .image(Data([0x01]), .centered))
    }

    @Test func stagedAvatarCasesAreDistinct() {
        #expect(StagedAvatar.none != .reverted)
        #expect(StagedAvatar.reverted != .image(Data([0x01]), nil))
        #expect(StagedAvatar.reframed(.centered) != .none)
    }

    @Test func aFramedUploadPreviewsWithItsFrame() {
        let data = Data([0x01, 0x02])
        let change = AvatarChangeUpload(
            bytes: data.toKotlinByteArray(),
            contentType: "image/jpeg",
            frame: ImageFrame(focusX: 0.4, focusY: 0.3, zoom: 2)
        )
        #expect(
            EditProfileObserver.stagedAvatar(for: change, pickedImage: data)
                == .image(data, ImageFrameValue(focusX: 0.4, focusY: 0.3, zoom: 2))
        )
    }

    @Test func aReframeShowsTheStoredAvatarWithTheNewFrame() {
        let change = AvatarChangeReframe(frame: ImageFrame(focusX: 0.5, focusY: 0.2, zoom: 1.5))
        #expect(
            EditProfileObserver.stagedAvatar(for: change, pickedImage: nil)
                == .reframed(ImageFrameValue(focusX: 0.5, focusY: 0.2, zoom: 1.5))
        )
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
