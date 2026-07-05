import SwiftUI
import Shared

/// A Swift-friendly projection of the VM's pending avatar change, carrying the picked
/// image bytes so the view can preview an upload without saving first.
enum StagedAvatar: Equatable {
    /// No pending change — render the user's current avatar.
    case none
    /// Pending revert to the auto-generated initials avatar.
    case reverted
    /// Pending upload of a freshly picked image.
    case image(Data)
}

/// Observes `EditProfileViewModel`, flattening `EditProfileUiState` into `@Observable`
/// properties and surfacing the one-shot `EditProfileEvent` stream as `lastError` /
/// `savedToken`.
///
/// The VM now owns the entire form buffer (name, tagline, passwords, avatar change), so
/// this observer is a pure mirror: read props reflect `.Ready`, and every keystroke /
/// avatar action passes straight through to a VM setter. The SwiftUI view keeps no
/// parallel `@State` copy.
@Observable
@MainActor
final class EditProfileObserver {
    private(set) var isLoading: Bool = true
    private(set) var user: User?
    private(set) var firstName: String = ""
    private(set) var lastName: String = ""
    private(set) var tagline: String = ""
    private(set) var currentPassword: String = ""
    private(set) var newPassword: String = ""
    private(set) var confirmPassword: String = ""
    private(set) var isDirty: Bool = false
    private(set) var isSaving: Bool = false
    /// Mirrors the observed `public_profiles` avatar type: true when the signed-in user currently
    /// has an uploaded image (so "Remove avatar" is offered). The single avatar source, not `User`.
    private(set) var hasImageAvatar: Bool = false
    private(set) var stagedAvatar: StagedAvatar = .none
    private(set) var lastError: String?

    /// Monotonic counter bumped on every successful save outcome, so the view can
    /// dismiss once a save lands without polling state flags.
    private(set) var savedToken: Int = 0

    /// Locally retained picked-image bytes, kept so an upload can be previewed without a
    /// round-trip through the (binary) VM state. Cleared whenever the staged change is no
    /// longer an upload.
    private var pickedImage: Data?

    private let viewModel: EditProfileViewModel
    private let bridge = FlowBridge()

    init(viewModel: EditProfileViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func setFirstName(_ value: String) { viewModel.setFirstName(value: value) }
    func setLastName(_ value: String) { viewModel.setLastName(value: value) }
    func setTagline(_ value: String) { viewModel.setTagline(value: value) }
    func setCurrentPassword(_ value: String) { viewModel.setCurrentPassword(value: value) }
    func setNewPassword(_ value: String) { viewModel.setNewPassword(value: value) }
    func setConfirmPassword(_ value: String) { viewModel.setConfirmPassword(value: value) }

    func stageAvatarUpload(_ data: Data) {
        pickedImage = data
        viewModel.stageAvatarUpload(bytes: data.toKotlinByteArray(), contentType: "image/jpeg")
    }

    func stageAvatarRevert() {
        pickedImage = nil
        viewModel.stageAvatarRevert()
    }

    func save() { viewModel.save() }

    func dismissError() { lastError = nil }

    // MARK: - State mapping

    private func apply(_ state: EditProfileUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
        case .ready(let r):
            isLoading = false
            user = r.user
            firstName = r.firstName
            lastName = r.lastName
            tagline = r.tagline
            currentPassword = r.currentPassword
            newPassword = r.newPassword
            confirmPassword = r.confirmPassword
            isDirty = r.isDirty
            isSaving = r.isSaving
            hasImageAvatar = r.hasImageAvatar
            stagedAvatar = Self.stagedAvatar(for: r.avatarChange, pickedImage: pickedImage)
        case .error:
            isLoading = false
            user = nil
        case .unknown:
            Log.error("Unexpected EditProfileUiState case")
            isLoading = false
            user = nil
        }
    }

    private func applyEvent(_ event: EditProfileEvent) {
        switch onEnum(of: event) {
        case .saveSucceeded:
            // The VM clears its staged avatar on success; drop our local preview copy too.
            pickedImage = nil
            savedToken += 1
        case .saveFailed(let failure):
            lastError = failure.message
        case .unknown:
            Log.error("Unexpected EditProfileEvent case")
        }
    }

    // MARK: - Pure mapping (unit-tested)

    /// Projects the VM's `AvatarChange` onto the Swift-friendly `StagedAvatar`. An upload
    /// is previewed from the locally retained `pickedImage`; if that's somehow absent we
    /// fall back to `.none` rather than fabricate empty image data.
    nonisolated static func stagedAvatar(for change: AvatarChange, pickedImage: Data?) -> StagedAvatar {
        switch onEnum(of: change) {
        case .none:
            return .none
        case .revertToAuto:
            return .reverted
        case .upload:
            return pickedImage.map(StagedAvatar.image) ?? .none
        case .unknown:
            Log.error("Unexpected AvatarChange case")
            return .none
        }
    }
}
