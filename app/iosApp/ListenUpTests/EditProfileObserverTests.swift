import Foundation
import Testing
@testable import ListenUp

/// Pure-seam coverage for the Edit Profile screen.
///
/// As with the other observer suites, the `apply` flatten over SKIE's sealed
/// `EditProfileUiState` can't be exercised from Swift (the cases aren't constructible),
/// so that mapping is proven at the green-build pass. What *is* pure and constructible
/// is the avatar `Remove`-affordance decision and the Swift-side `StagedAvatar`
/// projection — those seams are pinned here.
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
}
