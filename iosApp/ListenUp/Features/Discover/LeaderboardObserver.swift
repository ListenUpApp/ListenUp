import Foundation
import Shared

/// Observes `LeaderboardViewModel` — flattens the sealed `LeaderboardUiState` into a
/// SwiftUI-native `LeaderboardPhase` for the Discover leaderboard. Surfaces the period
/// control (Week / Month / All) and the metric control (Time / Books / Streak); the
/// snapshot already carries all three category rankings, so switching metric is a pure
/// state transformation with no upstream re-fetch.
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
    private(set) var selectedMetric: LeaderboardMetric = .time

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

    /// Switch the ranked metric (Time / Books / Streak). The snapshot already holds all three
    /// lists, so this re-maps the latest snapshot locally; it also nudges the shared VM to keep
    /// its category state in sync.
    func selectMetric(_ metric: LeaderboardMetric) {
        guard selectedMetric != metric else { return }
        selectedMetric = metric
        viewModel.selectCategory(c: metric.kmpCategory)
        apply(latestState)
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
            // The KMP state is `.data` when ANY category has entries; the selected metric's list
            // can still be empty (e.g. Books/Streak for a bounded period), so fall to `.empty`.
            let rows = Self.rows(from: data.snapshot, currentUserId: currentUserId, metric: selectedMetric)
            phase = rows.isEmpty ? .empty : .data(rows)
        case .error(let error):
            phase = .error(isRetryable: error.isRetryable)
        case .unknown:
            Log.error("Unexpected LeaderboardUiState case")
            phase = .error(isRetryable: false)
        }
    }

    /// Pure: map the ranking for `metric` to display rows, tagging the current user.
    /// `nonisolated` because it touches no actor state — callable from tests off the main actor.
    nonisolated static func rows(
        from snapshot: LeaderboardSnapshot,
        currentUserId: String?,
        metric: LeaderboardMetric = .time
    ) -> [LeaderboardRow] {
        metric.entries(in: snapshot).map { entry in
            LeaderboardRow(from: entry, isCurrentUser: entry.userId == currentUserId, metric: metric)
        }
    }
}

// MARK: - Metric selection

/// The three ranked metrics the leaderboard surfaces. Maps to the KMP `LeaderboardCategory`.
enum LeaderboardMetric: CaseIterable, Identifiable {
    case time
    case books
    case streak

    var id: Self { self }

    var kmpCategory: LeaderboardCategory {
        switch self {
        case .time: LeaderboardCategory.time
        case .books: LeaderboardCategory.books
        case .streak: LeaderboardCategory.streak
        }
    }

    /// The snapshot ranking for this metric.
    func entries(in snapshot: LeaderboardSnapshot) -> [LeaderboardEntry] {
        switch self {
        case .time: snapshot.time
        case .books: snapshot.books
        case .streak: snapshot.streak
        }
    }

    var titleKey: String.LocalizationValue {
        switch self {
        case .time: "discover.leaderboard_category_time"
        case .books: "discover.leaderboard_category_books"
        case .streak: "discover.leaderboard_category_streak"
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

    init(from entry: LeaderboardEntry, isCurrentUser: Bool, metric: LeaderboardMetric = .time) {
        self.id = entry.userId
        self.rank = Int(entry.rank)
        self.displayName = entry.displayName
        self.initials = Self.initials(from: entry.displayName)
        self.value = Self.value(for: entry, metric: metric)
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

    /// The value-column string for a metric: listen-time for Time, a book count for Books, a
    /// day count for Streak (longest run, matching the Android leaderboard).
    static func value(for entry: LeaderboardEntry, metric: LeaderboardMetric) -> String {
        switch metric {
        case .time:
            return formatHours(seconds: entry.totalSeconds)
        case .books:
            return String(format: String(localized: "common.books_count"), entry.booksFinished)
        case .streak:
            return String(format: String(localized: "common.n_days"), String(entry.longestStreakDays))
        }
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
