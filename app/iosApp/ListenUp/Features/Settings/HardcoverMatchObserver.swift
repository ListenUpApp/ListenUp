import SwiftUI
import Shared

/// Render phase for Find on Hardcover, flattened from `HardcoverMatchUiState` into native values.
enum HardcoverMatchPhase: Equatable {
    case loading
    case bookMissing
    case ready(HardcoverMatchModel)
}

/// What a search is showing. `failed` carries the error's own user-facing sentence.
enum HardcoverSearchPhase: Equatable {
    case searching
    /// What Hardcover found, split the way the sheet shows it: the book's own author first.
    case results(byAuthor: [HardcoverCandidate], others: [HardcoverCandidate])
    case noResults
    case failed(String)
}

/// One result, native: what it is, and the tells between the real book and a summary of it.
struct HardcoverCandidate: Equatable, Identifiable {
    let id: Int64
    let title: String
    /// "Andy Weir, Ray Porter", or "Unknown author" when Hardcover credits no one.
    let authors: String
    /// "Audiobook · 2021": whether Hardcover names an audiobook edition, then the year. Nil when
    /// it knows neither.
    let detail: String?
    /// "47,312 ratings", "1 rating", or "No ratings yet".
    let ratings: String
    let sharesAuthor: Bool
}

/// The book's match today, shown above the search with Remove Match.
struct HardcoverCurrentMatch: Equatable {
    let title: String
    let byline: String?
}

/// Find on Hardcover for one book, native.
struct HardcoverMatchModel: Equatable {
    let bookTitle: String
    /// "Andy Weir" — the book's credited authors, joined; empty when it has none.
    let bookAuthors: String
    /// What the search field holds.
    let query: String
    let search: HardcoverSearchPhase
    let current: HardcoverCurrentMatch?
    /// The result whose link is in flight.
    let linkingId: Int64?
    let isRemoving: Bool
    /// Other searches worth one tap: shown under No Results, and with a weak result set.
    let suggestions: [String]

    /// The author "By {author}" and the weak-results note name: the book's first credited author.
    var leadAuthor: String? {
        bookAuthors.components(separatedBy: ", ").first.flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Results came back, none by the book's author, and a better search is on offer: the sheet
    /// says so above them rather than letting a summary pass for the book.
    var isWeak: Bool {
        guard case .results(let byAuthor, _) = search else { return false }
        return byAuthor.isEmpty && leadAuthor != nil && !suggestions.isEmpty
    }

    /// Any action in flight: every result and Remove Match wait for it.
    var isBusy: Bool { linkingId != nil || isRemoving }
}

/// What a one-shot `HardcoverMatchEvent` does on iOS.
enum HardcoverMatchEffect: Equatable {
    /// The pick is linked, or the match removed: close the sheet. Book Detail then reads "Matched
    /// just now" — iOS offers no Undo, since the sheet that would hold it has gone.
    case dismiss
    case alert(String)
}

/// Observes `HardcoverMatchViewModel`: flattens its state, closes the sheet on a link or a removed
/// match, and turns errors into an alert (iosApp rule 10).
@Observable
@MainActor
final class HardcoverMatchObserver {
    private(set) var phase: HardcoverMatchPhase = .loading
    /// The error to show, from a failed link or removal. Cleared when the alert is dismissed.
    var alert: MessageAlert?
    /// Set when the sheet should close; the view watches it.
    private(set) var shouldDismiss = false

    private let viewModel: HardcoverMatchViewModel
    private let bridge = FlowBridge()

    init(viewModel: HardcoverMatchViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.uiState) { [weak self] in self?.phase = Self.phase(from: $0) }
        bridge.bind(viewModel.events) { [weak self] in self?.apply(Self.effect(of: $0)) }
    }

    deinit { bridge.cancelAll() }   // cancelAll() is nonisolated-safe; see FlowBridge.

    // MARK: - Actions

    /// The search field changed. Nothing is searched until `search()`: each search spends the user's
    /// Hardcover budget.
    func queryChanged(_ text: String) { viewModel.onQueryChange(text: text) }

    /// Searches for what the field holds — the keyboard's Search key.
    func search() { viewModel.search() }

    /// Searches for a suggestion, as if typed and submitted.
    func search(for text: String) { viewModel.searchFor(text: text) }

    /// Links the book to this result, replacing any match it has.
    func pick(_ id: Int64) { viewModel.link(hcBookId: id) }

    /// Removes the book's match; it then needs one. Callers confirm first.
    func removeMatch() { viewModel.removeMatch() }

    private func apply(_ effect: HardcoverMatchEffect) {
        switch effect {
        case .dismiss: shouldDismiss = true
        case .alert(let message): alert = MessageAlert(message: message)
        }
    }

    // MARK: - Pure mappings (unit-tested)

    nonisolated static func phase(from state: HardcoverMatchUiState) -> HardcoverMatchPhase {
        switch state.sealedType() {
        case .loading: return .loading
        case .bookMissing: return .bookMissing
        case .ready(let readyType):
            let ready = readyType.value
            return .ready(
                HardcoverMatchModel(
                    bookTitle: ready.bookTitle,
                    bookAuthors: ready.bookAuthors,
                    query: ready.query,
                    search: searchPhase(from: ready.search),
                    current: ready.currentMatch.map(currentMatch(from:)),
                    linkingId: ready.linkingId,
                    isRemoving: ready.isRemoving,
                    suggestions: ready.suggestions
                )
            )
        }
    }

    nonisolated static func searchPhase(from search: HardcoverSearchState) -> HardcoverSearchPhase {
        switch search.sealedType() {
        case .searching: return .searching
        case .noResults: return .noResults
        case .failed(let failedType): return .failed(failedType.value.error.message)
        case .results(let resultsType):
            // Already ranked author-first by the ViewModel; split here so each half is a section.
            let rows = resultsType.value.rows.map(candidate(from:))
            return .results(byAuthor: rows.filter(\.sharesAuthor), others: rows.filter { !$0.sharesAuthor })
        }
    }

    nonisolated static func effect(of event: HardcoverMatchEvent) -> HardcoverMatchEffect {
        switch event.sealedType() {
        case .linked, .matchRemoved: .dismiss
        case .showError(let errorType): .alert(errorType.value.error.message)
        }
    }

    nonisolated static func candidate(from row: HardcoverCandidateRow) -> HardcoverCandidate {
        let detail = [
            row.hasAudiobookEdition ? String(localized: "hardcover.match_audiobook") : nil,
            row.releaseYear.map { String($0) }
        ].compactMap { $0 }
        return HardcoverCandidate(
            id: row.hcBookId,
            title: row.title,
            authors: row.authors.isEmpty
                ? String(localized: "hardcover.match_unknown_author")
                : row.authors.prefix(2).joined(separator: ", "),
            detail: detail.isEmpty ? nil : detail.joined(separator: " · "),
            ratings: ratingsText(row.ratingsCount),
            sharesAuthor: row.sharesAuthor
        )
    }

    nonisolated static func currentMatch(from match: HardcoverMatchedBook) -> HardcoverCurrentMatch {
        HardcoverCurrentMatch(
            title: match.title ?? String(localized: "hardcover.book_row_matched_unnamed"),
            byline: byline(authors: match.authors, year: match.releaseYear)
        )
    }

    /// "Andy Weir, Ray Porter · 2021": two credited names (Hardcover lists narrators too), then the year.
    nonisolated static func byline(authors: [String], year: Int32?) -> String? {
        let parts = [authors.prefix(2).joined(separator: ", "), year.map { String($0) }]
            .compactMap { $0 }
            .filter { !$0.isEmpty }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    /// "47,312 ratings", "1 rating", or "No ratings yet", the count grouped for the reader's locale.
    nonisolated static func ratingsText(_ count: Int32?) -> String {
        guard let count, count > 0 else { return String(localized: "hardcover.match_ratings_none") }
        if count == 1 { return String(localized: "hardcover.match_ratings_one") }
        return String(format: String(localized: "hardcover.match_ratings"), Int(count).formatted())
    }
}
