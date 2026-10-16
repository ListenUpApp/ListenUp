import Foundation
import Shared

/// Observes `ActivityFeedViewModel` — flattens the sealed `ActivityFeedUiState` into a
/// SwiftUI-native `ActivityFeedPhase`. Each `ActivityUiModel` becomes an `ActivityRowItem`
/// carrying the actor ("You" on your own row), the shared sentence's parts, where the row leads,
/// and the timestamp (rendered with a native relative formatter).
///
/// Thin over `FlowBridge`; the row mapping lives in a pure, testable initializer.
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
        bridge.own(viewModel)
        bridge.bind(viewModel.state) { [weak self] in self?.apply($0) }
    }

    // MARK: - Actions

    func refresh() {
        viewModel.refresh()
    }

    // MARK: - State mapping

    private func apply(_ state: ActivityFeedUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = readyType.value
            phase = .ready(ready.activities.map(ActivityRowItem.init(from:)))
        case .error:
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

/// Where an activity row leads: its book, its shelf, or — for a row about neither (a join, a
/// streak) — the person who did it. Every row goes somewhere (spec X31).
enum ActivityDestination: Hashable {
    case book(String)
    case shelf(String)
    case profile(String)
}

/// One activity row: "**who** predicate **highlight** suffix" + a relative timestamp.
///
/// The sentence is the shared `activityParts` every platform reads (spec §2.8), so iOS says exactly
/// what Android and web say; only "You" on your own row is chosen here, from `isYou`.
struct ActivityRowItem: Identifiable, Equatable {
    let id: String
    let userId: String
    /// The actor as shown: their name, or "You" on your own row.
    let who: String
    /// The actor's own name, for their avatar's fallback initials.
    let actorName: String
    let isYou: Bool
    /// What they did — "finished", "listened to 48 minutes of".
    let predicate: String
    /// The book or shelf name, drawn in coral; nil when the event names nothing.
    let highlight: String?
    /// Trailing plain text — " by Frank Herbert" on a book row; empty otherwise.
    let suffix: String
    let bookId: String?
    let shelfId: String?
    /// The book's local cover file; nil when not downloaded or book-less.
    let coverPath: String?
    /// The book's cover content version; nil when it has none or the row is book-less.
    let coverHash: String?
    let occurredAt: Date

    /// The book when there is one, else the shelf, else the actor.
    var destination: ActivityDestination {
        if let bookId { return .book(bookId) }
        if let shelfId { return .shelf(shelfId) }
        return .profile(userId)
    }

    init(from model: ActivityUiModel) {
        let parts = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.discover.activityParts(
            activity: model
        )
        self.id = model.id
        self.userId = model.userId
        self.isYou = model.isYou
        self.actorName = model.userDisplayName
        self.who = model.isYou ? String(localized: "discover.activity_you") : model.userDisplayName
        self.predicate = parts.predicate
        self.highlight = parts.highlight
        self.suffix = parts.suffix
        self.bookId = model.bookId
        self.shelfId = model.shelfId
        self.coverPath = model.bookCoverPath
        self.coverHash = model.bookCoverHash
        self.occurredAt = Date(timeIntervalSince1970: TimeInterval(model.occurredAt) / 1_000)
    }

    init(
        id: String,
        userId: String,
        who: String,
        predicate: String,
        highlight: String?,
        suffix: String = "",
        bookId: String?,
        shelfId: String? = nil,
        occurredAt: Date,
        isYou: Bool = false,
        actorName: String? = nil,
        coverPath: String? = nil,
        coverHash: String? = nil
    ) {
        self.id = id
        self.userId = userId
        self.who = who
        self.actorName = actorName ?? who
        self.isYou = isYou
        self.predicate = predicate
        self.highlight = highlight
        self.suffix = suffix
        self.bookId = bookId
        self.shelfId = shelfId
        self.coverPath = coverPath
        self.coverHash = coverHash
        self.occurredAt = occurredAt
    }
}
