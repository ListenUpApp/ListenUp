import SwiftUI
import Shared

/// The render phase of the user profile screen, flattened from `UserProfileUiState`.
///
/// `Idle` and `Loading` from the shared VM both collapse to `.loading` here — the
/// screen has nothing to show until the profile resolves, and the distinction isn't
/// user-visible.
enum UserProfilePhase: Equatable {
    case loading
    case ready
    case error(String)
}

/// Pure, unit-tested formatters for the profile stat strip. Kept free of `Shared`
/// imports so the stat math is testable from Swift without constructing the
/// (non-constructible) sealed `UserProfileUiState`.
enum ProfileStatFormat {
    /// Compact listen-time for the stat strip: whole hours when ≥ 1h ("47h"),
    /// otherwise whole minutes ("12m"), and "0h" when there's nothing yet.
    static func listened(totalMs: Int64) -> String {
        let minutes = totalMs / 60_000
        let hours = minutes / 60
        if hours > 0 { return "\(hours)h" }
        if minutes > 0 { return "\(minutes)m" }
        return "0h"
    }
}

/// Observes `UserProfileViewModel`, flattening `UserProfileUiState` into flat
/// `@Observable` properties the SwiftUI screen binds to. Thin over `FlowBridge`.
@Observable
@MainActor
final class UserProfileObserver {
    private(set) var phase: UserProfilePhase = .loading
    private(set) var displayName: String = ""
    private(set) var tagline: String?
    /// True when the loaded profile is the signed-in user's own — lets a shared observer back both the
    /// own-profile and foreign-profile screens (the foreign screen renders read-only regardless).
    private(set) var isOwnProfile: Bool = false
    private(set) var totalListenTimeMs: Int64 = 0
    private(set) var booksFinished: Int = 0
    private(set) var currentStreak: Int = 0
    private(set) var longestStreak: Int = 0

    private let viewModel: UserProfileViewModel
    private let bridge = FlowBridge()

    init(viewModel: UserProfileViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    func loadProfile(userId: String) { viewModel.loadProfile(userId: userId, forceRefresh: false) }

    func refresh() { viewModel.refresh() }

    private func apply(_ state: UserProfileUiState) {
        switch onEnum(of: state) {
        case .idle, .loading:
            phase = .loading
        case .ready(let r):
            phase = .ready
            displayName = r.displayName
            tagline = r.tagline
            isOwnProfile = r.isOwnProfile
            totalListenTimeMs = r.totalListenTimeMs
            booksFinished = Int(r.booksFinished)
            currentStreak = Int(r.currentStreak)
            longestStreak = Int(r.longestStreak)
        case .error(let errorState):
            phase = .error(errorState.message)
        case .unknown:
            Log.error("Unexpected UserProfileUiState case")
            phase = .error(String(localized: "common.error"))
        }
    }
}
