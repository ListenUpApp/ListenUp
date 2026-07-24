import Foundation
import Shared

/// Observes `HomeStatsViewModel` — flattens the sealed `HomeStatsUiState` into a
/// SwiftUI-native `StatsPhase` for the Home stats section. Thin over `FlowBridge`;
/// the `Data` mapping lives in a pure, testable initializer.
@Observable
@MainActor
final class HomeStatsObserver {
    // MARK: - State

    private(set) var statsPhase: StatsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: HomeStatsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: HomeStatsViewModel = Dependencies.shared.homeStatsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - State mapping

    private func apply(_ state: HomeStatsUiState) {
        switch onEnum(of: state) {
        case .loading:
            statsPhase = .loading
        case .empty:
            statsPhase = .empty
        case .data(let data):
            statsPhase = .data(HomeStatsData(from: data))
        case .error:
            statsPhase = .error
        case .unknown:
            Log.error("Unexpected HomeStatsUiState case")
            statsPhase = .error
        }
    }
}

// MARK: - Phase

/// Flattened stats state for a SwiftUI `switch` — the section has four surfaces.
enum StatsPhase: Equatable {
    case loading
    case empty
    case data(HomeStatsData)
    case error
}

// MARK: - Data content

/// Everything the Home stats section renders when `HomeStatsUiState.Data` is active.
struct HomeStatsData: Equatable {
    let listenTimeLabel: String
    let currentStreak: Int
    let longestStreak: Int
    let hasStreak: Bool
    let days: [DayBar]
    let maxDaySeconds: Int
    let genres: [GenreBar]
    let hasGenreData: Bool

    init(from data: HomeStatsUiStateData) {
        self.listenTimeLabel = data.formattedListenTime
        self.currentStreak = Int(data.currentStreakDays)
        self.longestStreak = Int(data.longestStreakDays)
        self.hasStreak = data.hasStreak
        self.days = data.dailyBuckets.map {
            DayBar(dayOffset: Int($0.dayOffsetFromToday), seconds: Int($0.totalSeconds))
        }
        self.maxDaySeconds = Int(data.maxDailySeconds)
        self.genres = data.topGenres.map { GenreBar(name: $0.genreName, seconds: Int($0.totalSeconds)) }
        self.hasGenreData = data.hasGenreData
    }
}

/// One bar in the 7-day listening chart. `dayOffset` 0 = today, up to 6 = six days ago.
struct DayBar: Identifiable, Equatable {
    var id: Int { dayOffset }
    let dayOffset: Int
    let seconds: Int
}

/// One genre's share in the top-genres breakdown.
struct GenreBar: Identifiable, Equatable {
    var id: String { name }
    let name: String
    let seconds: Int
}
