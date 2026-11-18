import Foundation
@preconcurrency import Shared

/// Observes `ActivityFeedViewModel` — flattens the sealed `ActivityFeedUiState` into a
/// SwiftUI-native `ActivityFeedPhase`. Each entry becomes an `ActivityRowItem` carrying the actor
/// ("You" on your own row), the shared sentence's parts, where the row leads, and when — a
/// timestamp (rendered with a native relative formatter), or "Listening now" for someone listening
/// right now.
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

    /// `refresh()` for a pull-to-refresh: returns when the reconcile has finished.
    func refreshNow() async {
        try? await viewModel.refreshNow()
    }

    // MARK: - State mapping

    private func apply(_ state: ActivityFeedUiState) {
        switch state.sealedType() {
        case .loading:
            phase = .loading
        case .ready(let readyType):
            let ready = readyType.value
            phase = .ready(ready.entries.map(ActivityRowItem.init(entry:)))
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

/// When an activity row's event happened — or that it is happening now.
enum ActivityMoment: Equatable {
    /// Something that happened at this instant, shown as a relative time.
    case at(Date)
    /// Someone listening right now, shown as Discover's coral "Listening now".
    case listeningNow
}

/// One activity row: "**who** predicate **highlight** suffix" + when.
///
/// The sentence is the shared `activityParts` (or `listeningNowParts`) every platform reads, so iOS
/// says exactly what Android and web say; only "You" on your own row is chosen here, from `isYou`.
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
    /// When it happened, or that they are listening now.
    let moment: ActivityMoment
    /// What the row offers beyond its tap, from the shared `activityRowActions` rule.
    let actions: [ActivityRowActions.Action]
    /// The same, for a screen that cannot shelve one book (no selection, or one is under way).
    let actionsWithoutShelving: [ActivityRowActions.Action]

    /// The book when there is one, else the shelf, else the actor.
    var destination: ActivityDestination {
        if let bookId { return .book(bookId) }
        if let shelfId { return .shelf(shelfId) }
        return .profile(userId)
    }

    /// The row for one shared feed entry: an event, or someone listening right now.
    init(entry: any ActivityFeedEntry) {
        switch entry.sealedType() {
        case .activityUiModel(let event): self.init(from: event.value)
        case .listeningNowUiModel(let live): self.init(listening: live.value)
        }
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
        self.moment = .at(Date(timeIntervalSince1970: TimeInterval(model.occurredAt) / 1_000))
        let rules = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.discover.self
        self.actions = rules.activityRowActions(row: model, canShelve: true).map(ActivityRowActions.Action.init)
        self.actionsWithoutShelving = rules.activityRowActions(row: model, canShelve: false)
            .map(ActivityRowActions.Action.init)
    }

    /// "**Kyle Harnett** is listening to **Red Rising** by Pierce Brown", and "Listening now". Never yours: the
    /// shared model leaves the viewer's own session out.
    init(listening model: ListeningNowUiModel) {
        let rules = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.discover.self
        let parts = rules.listeningNowParts(listener: model)
        self.id = model.id
        self.userId = model.userId
        self.isYou = false
        self.actorName = model.userDisplayName
        self.who = model.userDisplayName
        self.predicate = parts.predicate
        self.highlight = parts.highlight
        self.suffix = parts.suffix
        self.bookId = model.bookId
        self.shelfId = nil
        self.coverPath = model.bookCoverPath
        self.coverHash = model.bookCoverHash
        self.moment = .listeningNow
        self.actions = rules.listeningNowRowActions(listener: model, canShelve: true).map(ActivityRowActions.Action.init)
        self.actionsWithoutShelving = rules.listeningNowRowActions(listener: model, canShelve: false)
            .map(ActivityRowActions.Action.init)
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
        coverHash: String? = nil,
        actions: [ActivityRowActions.Action] = [],
        actionsWithoutShelving: [ActivityRowActions.Action] = []
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
        self.moment = .at(occurredAt)
        self.actions = actions
        self.actionsWithoutShelving = actionsWithoutShelving
    }
}
