import Foundation
import Shared

/// Observes `ActivityFeedViewModel` — flattens the sealed `ActivityFeedUiState` into a
/// SwiftUI-native `ActivityFeedPhase`. Each `ActivityUiModel` becomes an `ActivityRowItem`
/// carrying the actor, an action phrase, the book (rendered in coral by the view), and the
/// timestamp (rendered with a native relative formatter).
///
/// Thin over `FlowBridge`; the phrase mapping lives in a pure, testable helper.
@Observable
@MainActor
final class ActivityFeedObserver {
    // MARK: - State

    private(set) var phase: ActivityFeedPhase = .loading

    // MARK: - Dependencies

    private let viewModel: ActivityFeedViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: ActivityFeedViewModel = Dependencies.shared.createActivityFeedViewModel()) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    func refresh() {
        viewModel.refresh()
    }

    // MARK: - State mapping

    private func apply(_ state: ActivityFeedUiState) {
        switch onEnum(of: state) {
        case .loading:
            phase = .loading
        case .ready(let ready):
            phase = .ready(ready.activities.map(ActivityRowItem.init(from:)))
        case .error:
            phase = .error
        case .unknown:
            Log.error("Unexpected ActivityFeedUiState case")
            phase = .error
        }
    }
}

// MARK: - Phase

/// Flattened activity-feed state for a SwiftUI `switch`.
enum ActivityFeedPhase: Equatable {
    case loading
    case ready([ActivityRowItem])
    case error
}

// MARK: - Row model

/// One activity row: "**who** *action* **book**" + a relative timestamp. The `book`
/// fragment is optional — milestone/join activities have no book and render the phrase only.
struct ActivityRowItem: Identifiable, Equatable {
    let id: String
    let userId: String
    let who: String
    let initials: String
    /// The action phrase ("finished", "started", "hit a listening streak").
    let action: String
    /// The book title, rendered in coral by the view; nil for book-less activities.
    let book: String?
    /// The book id, for navigation; nil for book-less activities.
    let bookId: String?
    let occurredAt: Date

    init(from model: ActivityUiModel) {
        self.id = model.id
        self.userId = model.userId
        self.who = model.userDisplayName
        self.initials = LeaderboardRow.initials(from: model.userDisplayName)
        self.action = ActivityRowItem.action(for: model)
        self.book = model.bookTitle
        self.bookId = model.bookId
        self.occurredAt = Date(timeIntervalSince1970: TimeInterval(model.occurredAt) / 1_000)
    }

    init(
        id: String,
        userId: String,
        who: String,
        initials: String,
        action: String,
        book: String?,
        bookId: String?,
        occurredAt: Date
    ) {
        self.id = id
        self.userId = userId
        self.who = who
        self.initials = initials
        self.action = action
        self.book = book
        self.bookId = bookId
        self.occurredAt = occurredAt
    }

    /// Pure: map an activity's `type` (+ `isReread`) to its localized action phrase.
    /// Mirrors the Android `getActivityIconAndDescription` type switch, surfaced as the
    /// design's terse "**who** action **book**" verb.
    static func action(for model: ActivityUiModel) -> String {
        switch model.type {
        case "started_book":
            String(localized: model.isReread ? "discover.activity_reread" : "discover.activity_started")
        case "finished_book":
            String(localized: "discover.activity_finished")
        case "listening_session":
            "\(String(localized: "discover.activity_listened_to")) \(durationWords(ms: model.durationMs)) of"
        case "streak_milestone":
            String(localized: "discover.activity_streak")
        case "listening_milestone":
            String(localized: "discover.activity_listened_milestone")
        case "shelf_created":
            String(localized: "discover.activity_shelf_created")
        case "user_joined":
            String(localized: "discover.activity_joined")
        default:
            String(localized: "discover.activity_did_something")
        }
    }

    /// Pure: long-form session length woven into the listening-session phrase
    /// ("30 seconds", "5 minutes", "1 hour", "1 hour 30 minutes"). Mirrors Android's
    /// `formatDurationMinutes` verbatim — whole-minute rounding once past a minute, seconds
    /// below that, singular/plural units — so the two platforms read identically.
    private static func durationWords(ms: Int64) -> String {
        let totalSeconds = Int(max(0, ms) / 1_000)
        let totalMinutes = totalSeconds / 60
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60

        if totalMinutes == 0 {
            return "\(totalSeconds) second\(totalSeconds == 1 ? "" : "s")"
        } else if hours == 0 {
            return "\(minutes) minute\(minutes == 1 ? "" : "s")"
        } else if minutes == 0 {
            return "\(hours) hour\(hours == 1 ? "" : "s")"
        } else {
            return "\(hours) hour\(hours == 1 ? "" : "s") \(minutes) minute\(minutes == 1 ? "" : "s")"
        }
    }
}
