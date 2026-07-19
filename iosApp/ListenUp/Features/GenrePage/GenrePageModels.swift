import SwiftUI
import Shared

/// The render phase of the genre destination page, flattened from `GenreDestinationUiState`.
enum GenrePagePhase: Equatable {
    case loading
    case ready
    case notFound
}

/// One breadcrumb ancestor, root-first — a native projection of the shared `GenreCrumb`.
struct GenreCrumbRow: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
}

/// One direct sub-genre chip — a native projection of the shared `SubGenre`, with its own accent
/// hue derived from its name via the shared `FacetIdentity.hue(name:)` — the same derivation that
/// sub-genre's own destination page uses once tapped, so the chip's dot previews the color it
/// lands on.
struct SubGenreRow: Identifiable, Equatable, Hashable {
    let id: String
    let name: String
    let bookCount: Int
    let hue: Color
}

/// A flat projection of `GenreDestinationUiState`, computed once so the mapping is pure and
/// unit-testable rather than buried in the observer's `apply`. Mirrors `FacetBooksSnapshot`.
struct GenrePageSnapshot: Equatable {
    var phase: GenrePagePhase = .loading
    var name: String = ""
    var slug: String = ""
    var blurb: String?
    var symbolName: String = "book"
    var hue: Color = .gray
    var breadcrumb: [GenreCrumbRow] = []
    var subGenres: [SubGenreRow] = []
    var hasSubs: Bool = false
    var includeSubGenres: Bool = false
    var bookCount: Int = 0
    var totalDurationMs: Int64 = 0
    var books: [BookRow] = []

    /// Flatten the sealed shared state into the flat snapshot the UI renders. The route-supplied
    /// `fallbackName` keeps the header titled while `Loading`.
    static func from(_ state: GenreDestinationUiState, fallbackName: String) -> GenrePageSnapshot {
        switch onEnum(of: state) {
        case .loading:
            return GenrePageSnapshot(phase: .loading, name: fallbackName)
        case .ready(let r):
            let identity = r.identity
            return GenrePageSnapshot(
                phase: .ready,
                name: identity.name,
                slug: identity.slug,
                blurb: identity.blurb,
                symbolName: sfSymbol(for: identity.icon),
                hue: hueColor(identity.hue),
                breadcrumb: r.breadcrumb.map { GenreCrumbRow(id: $0.genreId.value, name: $0.name) },
                subGenres: r.subGenres.map { sub in
                    SubGenreRow(
                        id: sub.genreId.value,
                        name: sub.name,
                        bookCount: Int(sub.bookCount),
                        hue: hueColor(FacetIdentity.shared.hue(name: sub.name))
                    )
                },
                hasSubs: r.hasSubs,
                includeSubGenres: r.includeSubGenres,
                bookCount: Int(r.stats.bookCount),
                totalDurationMs: r.stats.totalDurationMs,
                books: r.books.map { BookRow($0) }
            )
        case .notFound:
            return GenrePageSnapshot(phase: .notFound, name: fallbackName)
        case .unknown:
            Log.error("Unexpected GenreDestinationUiState case")
            return GenrePageSnapshot(phase: .notFound, name: fallbackName)
        }
    }
}
