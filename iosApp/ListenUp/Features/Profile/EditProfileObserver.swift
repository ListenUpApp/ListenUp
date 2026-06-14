import SwiftUI
@preconcurrency import Shared

/// Observes `EditProfileViewModel`, flattening `EditProfileUiState` into `@Observable`
/// properties and surfacing the one-shot `EditProfileEvent` stream as `lastError` /
/// `savedToken`.
///
/// Unlike the series/contributor edit VMs (MVI events + navActions), this VM exposes
/// imperative save methods and an `events` flow — text input lives in the SwiftUI view
/// and is handed over complete at save time. The observer mirrors the same shape.
@Observable
@MainActor
final class EditProfileObserver {
    private(set) var isLoading: Bool = true
    private(set) var user: User_?
    private(set) var localAvatarPath: String?
    private(set) var isSaving: Bool = false
    private(set) var lastError: String?

    /// Monotonic counter bumped on every successful save outcome, so the view can
    /// dismiss once a save lands without polling state flags.
    private(set) var savedToken: Int = 0

    private let viewModel: EditProfileViewModel
    private let bridge = FlowBridge()

    init(viewModel: EditProfileViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.events) { [weak self] in self?.applyEvent($0) }
    }

    deinit { MainActor.assumeIsolated { bridge.cancelAll() } }

    func stopObserving() { bridge.cancelAll() }

    // MARK: - Actions

    func saveTagline(_ tagline: String) { viewModel.saveTagline(tagline: tagline) }

    func saveName(firstName: String, lastName: String) {
        viewModel.saveName(firstName: firstName, lastName: lastName)
    }

    func changePassword(current: String, new: String) {
        viewModel.changePassword(currentPassword: current, newPassword: new)
    }

    func uploadAvatar(_ data: Data) {
        viewModel.uploadAvatar(imageData: data.toKotlinByteArray(), contentType: "image/jpeg")
    }

    func dismissError() { lastError = nil }

    // MARK: - State mapping

    private func apply(_ state: EditProfileUiState) {
        switch onEnum(of: state) {
        case .loading:
            isLoading = true
        case .ready(let r):
            isLoading = false
            user = r.user
            localAvatarPath = r.localAvatarPath
            isSaving = r.isSaving
        case .error:
            isLoading = false
            user = nil
        }
    }

    private func applyEvent(_ event: EditProfileEvent) {
        switch onEnum(of: event) {
        case .taglineSaved, .nameSaved, .avatarUpdated, .passwordChanged:
            savedToken += 1
        case .saveFailed(let failure):
            lastError = failure.message
        }
    }
}
