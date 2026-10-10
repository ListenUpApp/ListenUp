import Foundation
import SwiftUI
import Shared

/// Observes `BookRatingsViewModel`: flattens the sealed `BookRatingsUiState` into a native
/// `BookRatingsPhase` for the rating block on Book Detail, and forwards setStars, rate, clear, and
/// refreshExternal back to the VM. A removed rating is announced to VoiceOver: iOS has no undo toast. Everything the VM reads comes from Room, so the block works
/// offline and updates the moment a rating syncs in from another device — both your listeners' and
/// the outside world's.
///
/// Kotlin values are projected to Swift value types at this boundary, so the view never holds a
/// bridged object. Thin over `FlowBridge`; the mapping is a pure, testable static.
@Observable
@MainActor
final class BookRatingsObserver {
    // MARK: - State

    private(set) var phase: BookRatingsPhase = .loading

    // MARK: - Dependencies

    private let viewModel: BookRatingsViewModel
    private let bridge = FlowBridge()

    // MARK: - Init

    init(viewModel: BookRatingsViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.state) { [weak self] in self?.phase = BookRatingsObserver.phase(from: $0) }
        bridge.bind(viewModel.events) { VoiceOverAnnouncement.post(BookRatingsObserver.announcement(for: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// Save the stars you settled on — a tap, where a drag lifts, or a VoiceOver step — keeping your note.
    func setStars(_ halfStars: Int) { viewModel.setStars(halfStars: Int32(halfStars)) }

    /// Rate the book `halfStars` (2...10) with an optional note; the VM trims a blank note away.
    func rate(halfStars: Int, note: String) {
        viewModel.rate(halfStars: Int32(halfStars), note: note)
    }

    /// Remove my rating.
    func clear() { viewModel.clear() }

    /// Re-fetch every enabled outside source for this book now — admin only
    /// (``BookRatingsSnapshot/canRefresh``). A tap while one is already in flight is a no-op on the
    /// VM side, so this can be called freely.
    func refreshExternal() { viewModel.refreshExternal() }

    // MARK: - Effects

    /// What VoiceOver hears for a one-shot effect. iOS offers no Undo (there is no toast), so a removal is
    /// announced instead. `nonisolated` so tests can call it off the main actor.
    nonisolated static func announcement(for event: BookRatingsEvent) -> String {
        switch event.sealedType() {
        case .ratingRemoved:
            return String(localized: "book.detail_rating_removed")
        }
    }

    // MARK: - State mapping

    /// Pure: project the shared state onto native values. `nonisolated` so tests can call it off
    /// the main actor.
    nonisolated static func phase(from state: BookRatingsUiState) -> BookRatingsPhase {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .ready(let readyType):
            let ready = readyType.value
            let external = ready.external.map {
                ExternalScore(
                    average: $0.average,
                    count: Int($0.count),
                    outsideShares: Dictionary(
                        $0.outsideShares.map { ($0.source, $0.share) },
                        uniquingKeysWith: { first, _ in first }
                    ),
                    listenersShare: $0.listenersShare
                )
            }
            return .ready(BookRatingsSnapshot(
                listeners: ready.listeners.map {
                    ListenersAverage(averageHalfStars: $0.averageHalfStars, count: Int($0.count))
                },
                mine: ready.mine.map { MyRating(halfStars: Int($0.halfStars), note: $0.note, fromHardcover: $0.fromHardcover) },
                external: external,
                breakdown: ready.breakdown.map {
                    ExternalRatingRow(
                        source: $0.source,
                        average: $0.average,
                        count: Int($0.count),
                        fetchedAtMs: $0.fetchedAtMs
                    )
                },
                canRefresh: ready.canRefresh,
                isRefreshingExternal: ready.isRefreshingExternal,
                isCheckingExternal: ready.isCheckingExternal,
                scoreRow: scoreRow(from: ready.scoreRow, external: external),
                showsInlineRefresh: ready.showsInlineRefresh,
                // Read `.source` off each row: Swift Export traps on the elements of a bridged enum list.
                outsideSourcesInScore: ready.outsideRatingsInScore.map(\.source),
                listenersInScore: ready.listenersInScore
            ))
        }
    }

    /// The shared decision about the score's row, as a native value. `.shown` reuses the score already
    /// projected, so the row and the snapshot never disagree.
    nonisolated static func scoreRow(from row: ScoreRow, external: ExternalScore?) -> ScoreRowModel {
        switch row.sealedType() {
        case .shown: return external.map { .shown($0) } ?? .absent
        case .checking: return .checking
        case .noRatings: return .noRatings
        case .absent: return .absent
        }
    }
}

// MARK: - Phase

/// The rating block's state. `loading` renders nothing, so the block never flashes "Rate" at
/// someone who already has.
enum BookRatingsPhase: Equatable {
    case loading
    case ready(BookRatingsSnapshot)
}

/// What the rating block shows once the ratings are read.
struct BookRatingsSnapshot: Equatable {
    /// Your listeners' average, or nil when nobody has rated the book.
    let listeners: ListenersAverage?
    /// The signed-in listener's rating, or nil when they haven't rated it.
    let mine: MyRating?
    /// The ListenUp score, or nil when no enabled source (nor any listener) has rated the book yet.
    /// A score only your listeners gave is still here — `scoreRow` decides not to show it.
    let external: ExternalScore?
    /// Every enabled source's rating backing [external], highest rating count first — the sheet
    /// one tap away from the score's row.
    let breakdown: [ExternalRatingRow]
    /// Whether the signed-in listener may trigger `BookRatingsObserver.refreshExternal()`.
    let canRefresh: Bool
    /// Whether a refresh is currently in flight — the refresh button's busy/disabled state binds
    /// to this directly rather than guessing locally.
    let isRefreshingExternal: Bool
    /// Whether the server is fetching this book's Hardcover rating because Book Detail opened it.
    var isCheckingExternal: Bool = false
    /// The ListenUp score's row, as the shared state decided it.
    var scoreRow: ScoreRowModel = .noRatings
    /// Whether "Refresh ratings" sits in the section (admin, and no score row to open the sources from).
    var showsInlineRefresh: Bool = false
    /// The outside catalogs the score draws on, in the breakdown's order.
    var outsideSourcesInScore: [ExternalRatingSource] = []
    /// Whether your listeners are part of the score.
    var listenersInScore: Bool = false
}

/// The ListenUp score's row: shown, held by "Checking Hardcover…", "No ratings yet", or absent (only your
/// listeners rated the book, and their own row says so).
enum ScoreRowModel: Equatable {
    case shown(ExternalScore)
    case checking
    case noRatings
    case absent
}

/// Your listeners' average on the 2...10 half-star scale, over `count` ratings.
struct ListenersAverage: Equatable {
    let averageHalfStars: Double
    let count: Int

    /// "4.0": one decimal, like the score — the same arithmetic as the shared `RatingLabels.listenerAverageLabel`.
    var label: String { RatingLabels.shared.averageLabel(average: averageHalfStars / 2) }
}

/// The signed-in listener's own rating.
struct MyRating: Equatable {
    let halfStars: Int
    let note: String?
    /// Imported from Hardcover and not touched since: the card says "Rated on Hardcover".
    let fromHardcover: Bool
}

/// The ListenUp score: every enabled outside catalog, plus your listeners, each on its own curve
/// and combined onto one — with how much of the score each source carries.
struct ExternalScore: Equatable {
    let average: Double
    let count: Int
    /// Each outside catalog's share of the score (0...1); a catalog with no ratings is absent.
    let outsideShares: [ExternalRatingSource: Double]
    /// Your listeners' share of the score, or nil when they are not part of it.
    let listenersShare: Double?

    init(
        average: Double,
        count: Int,
        outsideShares: [ExternalRatingSource: Double] = [:],
        listenersShare: Double? = nil
    ) {
        self.average = average
        self.count = count
        self.outsideShares = outsideShares
        self.listenersShare = listenersShare
    }

    /// How many sources the score was combined from ("Combined from N sources").
    var sourceCount: Int { outsideShares.count + (listenersShare == nil ? 0 : 1) }

}

/// One outside catalog's rating of the book — a row in the sources sheet.
struct ExternalRatingRow: Equatable {
    let source: ExternalRatingSource
    let average: Double
    let count: Int
    /// When the server last fetched it (epoch ms); nil from an older server.
    var fetchedAtMs: Int64?
}
