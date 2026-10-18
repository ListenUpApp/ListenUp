import Foundation
import Shared

/// Observes `LeaderboardViewModel` — flattens the sealed `LeaderboardUiState` into a
/// SwiftUI-native `LeaderboardPhase` for the Discover leaderboard. Surfaces the period
/// control (Week / Month / Year / All Time) and the metric control (Time / Books / Streak); the
/// snapshot already carries all three category rankings, so switching metric is a pure
/// state transformation with no upstream re-fetch.
///
/// The current-user "You" row comes from `LeaderboardEntry.isYou`, which the shared
/// repository sets from the signed-in reader. The period and metric follow the shared
/// `LeaderboardViewModel.selection`, which exists in every board state.
///
/// Thin over `FlowBridge`; the `Data` mapping lives in a pure, testable helper.
@Observable
@MainActor
final class LeaderboardObserver {
    // MARK: - State

    private(set) var phase: LeaderboardPhase = .loading
    private(set) var selectedPeriod: LeaderboardPeriodOption = .week
    private(set) var selectedMetric: LeaderboardMetric = .time

    // MARK: - Dependencies

    private let viewModel: LeaderboardViewModel
    private let bridge = FlowBridge()
    private var latestState: LeaderboardUiState = LeaderboardUiStateLoading.shared

    // MARK: - Init

    init(viewModel: LeaderboardViewModel = Dependencies.shared.createLeaderboardViewModel()) {
        self.viewModel = viewModel
        bridge.own(viewModel)
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
        bridge.bind(viewModel.selection) { [weak self] in self?.applySelection($0) }
    }

    // MARK: - Actions

    /// Re-runs a failed leaderboard; a healthy one is left alone by the shared VM.
    func refresh() {
        viewModel.refresh()
    }

    /// Optimistic locally for an instant control; the shared selection then confirms it.
    func selectPeriod(_ option: LeaderboardPeriodOption) {
        selectedPeriod = option
        viewModel.selectPeriod(p: option.kmpPeriod)
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

    // MARK: - State mapping

    private func applySelection(_ selection: LeaderboardSelection) {
        selectedPeriod = LeaderboardPeriodOption(kmp: selection.period)
        selectedMetric = LeaderboardMetric(kmp: selection.category)
        apply(latestState)
    }

    private func apply(_ state: LeaderboardUiState) {
        latestState = state
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .empty:
            phase = .empty
        case .data(let dataType):
            let data = dataType.value
            // The KMP state is `.data` when ANY category has entries; the selected metric's list
            // can still be empty (e.g. Books/Streak for a bounded period), so fall to `.empty`.
            let rows = Self.rows(from: data.snapshot, metric: selectedMetric)
            phase = rows.isEmpty ? .empty : .data(rows)
        case .error:
            phase = .error
        }
    }

    /// Pure: map the ranking for `metric` to display rows; the reader's own row is the entry
    /// marked `isYou`. `nonisolated` because it touches no actor state — callable from tests off
    /// the main actor.
    nonisolated static func rows(
        from snapshot: LeaderboardSnapshot,
        metric: LeaderboardMetric = .time
    ) -> [LeaderboardRow] {
        metric.entries(in: snapshot).map { entry in
            LeaderboardRow(from: entry, isCurrentUser: entry.isYou, metric: metric)
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

    /// The metric a Kotlin category names; `.time` for one this client does not know.
    init(kmp category: LeaderboardCategory) {
        self = LeaderboardMetric.allCases.first { $0.kmpCategory == category } ?? .time
    }

    var kmpCategory: LeaderboardCategory {
        switch self {
        case .time: LeaderboardCategory.Time
        case .books: LeaderboardCategory.Books
        case .streak: LeaderboardCategory.Streak
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

/// The leaderboard's periods, in the order every platform shows them, named with the one wording
/// every platform uses: Week, Month, Year, All Time (title case, per the HIG). The visible word is the
/// accessible name, so there is no separate VoiceOver label. Maps to the KMP `LeaderboardPeriod`. Named
/// apart from the shared `LeaderboardSelection` (period + category), which it would otherwise shadow.
enum LeaderboardPeriodOption: CaseIterable, Identifiable {
    case week
    case month
    case year
    case allTime

    var id: Self { self }

    /// The option for a Kotlin period; `.week` for one this client does not know.
    init(kmp period: LeaderboardPeriod) {
        switch period {
        case is LeaderboardPeriodMonth: self = .month
        case is LeaderboardPeriodYear: self = .year
        case is LeaderboardPeriodAllTime: self = .allTime
        default: self = .week
        }
    }

    var kmpPeriod: LeaderboardPeriod {
        switch self {
        case .week: LeaderboardPeriodWeek.shared
        case .month: LeaderboardPeriodMonth.shared
        case .year: LeaderboardPeriodYear.shared
        case .allTime: LeaderboardPeriodAllTime.shared
        }
    }

    var titleKey: String.LocalizationValue {
        switch self {
        case .week: "discover.leaderboard_period_week"
        case .month: "discover.leaderboard_period_month"
        case .year: "discover.leaderboard_period_year"
        case .allTime: "discover.leaderboard_period_all_time_ios"
        }
    }
}

// MARK: - Phase

/// Flattened leaderboard state for a SwiftUI `switch`.
enum LeaderboardPhase: Equatable {
    case loading
    case empty
    case data([LeaderboardRow])
    case error
}

// MARK: - Row model

/// One ranked row. `value` is the formatted listen-time for the period; `initials` and a
/// neutral avatar are derived from `displayName`. `isCurrentUser` makes the row read "You" on a
/// tinted fill.
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
