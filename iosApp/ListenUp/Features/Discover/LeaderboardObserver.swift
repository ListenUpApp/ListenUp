import Foundation
import Shared

/// Observes `LeaderboardViewModel` — flattens the sealed `LeaderboardUiState` into a
/// SwiftUI-native `LeaderboardPhase` for the Discover leaderboard. Surfaces only the
/// **Time** category (the design shows no category tabs) and the period control
/// (Week / Month / All).
///
/// The current-user "(You)" highlight is resolved here: `LeaderboardEntry` carries no
/// `isCurrentUser` flag, only a `userId`, so the screen passes the signed-in user's id
/// via `setCurrentUserId(_:)` and each row compares against it.
///
/// Thin over `FlowBridge`; the `Data` mapping lives in a pure, testable helper.
@Observable
@MainActor
final class LeaderboardObserver {
    // MARK: - State

    private(set) var phase: LeaderboardPhase = .loading
    private(set) var selectedPeriod: LeaderboardSelection = .week

    // MARK: - Dependencies

    private let viewModel: LeaderboardViewModel
    private let bridge = FlowBridge()

    /// The signed-in user's id, used to tag the "(You)" row. Re-applied to the latest
    /// snapshot whenever it changes so the highlight appears as soon as the user loads.
    private var currentUserId: String?
    private var latestState: LeaderboardUiState = LeaderboardUiStateLoading.shared

    // MARK: - Init

    init(viewModel: LeaderboardViewModel = Dependencies.shared.createLeaderboardViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func selectPeriod(_ selection: LeaderboardSelection) {
        selectedPeriod = selection
        viewModel.selectPeriod(p: selection.kmpPeriod)
    }

    /// Update the id used for the "(You)" highlight, re-tagging the current snapshot.
    func setCurrentUserId(_ id: String?) {
        guard currentUserId != id else { return }
        currentUserId = id
        apply(latestState)
    }

    // MARK: - State mapping

    private func apply(_ state: LeaderboardUiState) {
        latestState = state
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .empty:
            phase = .empty
        case .data(let data):
            phase = .data(Self.rows(from: data.snapshot, currentUserId: currentUserId))
        case .error(let error):
            phase = .error(isRetryable: error.isRetryable)
        case .unknown:
            Log.error("Unexpected LeaderboardUiState case")
            phase = .error(isRetryable: false)
        }
    }

    /// Pure: map the snapshot's `time` ranking to display rows, tagging the current user.
    /// `time` is the headline ranking the design surfaces; category tabs are not shown.
    /// `nonisolated` because it touches no actor state — callable from tests off the main actor.
    nonisolated static func rows(from snapshot: LeaderboardSnapshot, currentUserId: String?) -> [LeaderboardRow] {
        snapshot.time.map { entry in
            LeaderboardRow(from: entry, isCurrentUser: entry.userId == currentUserId)
        }
    }
}

// MARK: - Period selection

/// The three period options the design surfaces. Maps to the KMP `LeaderboardPeriod`
/// (which also has `Year`, not exposed here per the design's Week / Month / All control).
enum LeaderboardSelection: CaseIterable, Identifiable {
    case week
    case month
    case all

    var id: Self { self }

    var kmpPeriod: LeaderboardPeriod {
        switch self {
        case .week: LeaderboardPeriodWeek.shared
        case .month: LeaderboardPeriodMonth.shared
        case .all: LeaderboardPeriodAllTime.shared
        }
    }

    var titleKey: String.LocalizationValue {
        switch self {
        case .week: "discover.period_week"
        case .month: "discover.period_month"
        case .all: "discover.period_all"
        }
    }
}

// MARK: - Phase

/// Flattened leaderboard state for a SwiftUI `switch`.
enum LeaderboardPhase: Equatable {
    case loading
    case empty
    case data([LeaderboardRow])
    case error(isRetryable: Bool)
}

// MARK: - Row model

/// One ranked row. `value` is the formatted listen-time for the period; `initials` and a
/// neutral avatar are derived from `displayName`. `isCurrentUser` drives the coral "(You)"
/// treatment.
struct LeaderboardRow: Identifiable, Equatable {
    let id: String
    let rank: Int
    let displayName: String
    let initials: String
    let value: String
    let isCurrentUser: Bool

    init(from entry: LeaderboardEntry, isCurrentUser: Bool) {
        self.id = entry.userId
        self.rank = Int(entry.rank)
        self.displayName = entry.displayName
        self.initials = Self.initials(from: entry.displayName)
        self.value = Self.formatHours(seconds: entry.totalSeconds)
        self.isCurrentUser = isCurrentUser
    }

    init(id: String, rank: Int, displayName: String, initials: String, value: String, isCurrentUser: Bool) {
        self.id = id
        self.rank = rank
        self.displayName = displayName
        self.initials = initials
        self.value = value
        self.isCurrentUser = isCurrentUser
    }

    /// Up to two uppercase initials from the first two words of a display name.
    static func initials(from name: String) -> String {
        let words = name.split(separator: " ").prefix(2)
        let letters = words.compactMap { $0.first.map(String.init) }
        return letters.joined().uppercased()
    }

    /// Format a listen-time total as "14h 20m" / "48m" — matches the design's value column.
    static func formatHours(seconds: Int64) -> String {
        let totalMinutes = Int(seconds / 60)
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        if hours == 0 {
            return "\(minutes)m"
        }
        return String(format: "%dh %02dm", hours, minutes)
    }
}
