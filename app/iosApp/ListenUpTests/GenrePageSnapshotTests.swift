import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the genre destination page's observer boundary:
/// `GenrePageSnapshot.from(_:fallbackName:)` flattening `GenreDestinationUiState` into the native
/// value types `GenrePageView` renders. Mirrors `MetadataMatchMappingTests` / `ContributorBooksSnapshotTests` —
/// hand-built Kotlin DTOs, no observer, no flow.
@Suite("GenrePageSnapshot")
struct GenrePageSnapshotTests {
    private static let fallback = "Fantasy"

    @Test func loadingUsesTheRouteSuppliedFallbackName() {
        let snapshot = GenrePageSnapshot.from(GenreDestinationUiStateLoading.shared, fallbackName: Self.fallback)
        #expect(snapshot.phase == .loading)
        #expect(snapshot.name == Self.fallback)
    }

    @Test func notFoundUsesTheRouteSuppliedFallbackName() {
        let snapshot = GenrePageSnapshot.from(GenreDestinationUiStateNotFound.shared, fallbackName: Self.fallback)
        #expect(snapshot.phase == .notFound)
        #expect(snapshot.name == Self.fallback)
    }

    @Test func readyWithSubsMapsIdentityHierarchyAndStats() {
        let state = GenreDestinationUiStateReady(
            identity: GenreIdentity(
                name: "Fantasy",
                slug: "fantasy",
                blurb: "Dragons, magic, and epic quests.",
                icon: .fantasy,
                hue: "#2E5AA0"
            ),
            breadcrumb: [GenreCrumb(genreId: GenreId(value: "fiction"), name: "Fiction")],
            subGenres: [
                SubGenre(genreId: GenreId(value: "epic-fantasy"), name: "Epic Fantasy", bookCount: 5),
                SubGenre(genreId: GenreId(value: "urban-fantasy"), name: "Urban Fantasy", bookCount: 3)
            ],
            hasSubs: true,
            includeSubGenres: true,
            stats: FacetStats(bookCount: 42, totalDurationMs: 3_600_000),
            books: []
        )

        let snapshot = GenrePageSnapshot.from(state, fallbackName: Self.fallback)

        #expect(snapshot.phase == .ready)
        #expect(snapshot.name == "Fantasy")
        #expect(snapshot.slug == "fantasy")
        #expect(snapshot.blurb == "Dragons, magic, and epic quests.")
        #expect(snapshot.symbolName == sfSymbol(for: .fantasy))
        #expect(snapshot.hasSubs == true)
        #expect(snapshot.includeSubGenres == true)
        #expect(snapshot.bookCount == 42)
        #expect(snapshot.totalDurationMs == 3_600_000)
        #expect(snapshot.books.isEmpty)

        #expect(snapshot.breadcrumb.map(\.id) == ["fiction"])
        #expect(snapshot.breadcrumb.map(\.name) == ["Fiction"])

        #expect(snapshot.subGenres.map(\.id) == ["epic-fantasy", "urban-fantasy"])
        #expect(snapshot.subGenres.map(\.name) == ["Epic Fantasy", "Urban Fantasy"])
        #expect(snapshot.subGenres.map(\.bookCount) == [5, 3])
    }

    @Test func readyOnALeafGenreDropsSubGenresButKeepsTheBreadcrumb() {
        let state = GenreDestinationUiStateReady(
            identity: GenreIdentity(
                name: "Epic Fantasy", slug: "epic-fantasy", blurb: nil, icon: .fantasy, hue: "#2E5AA0"
            ),
            breadcrumb: [
                GenreCrumb(genreId: GenreId(value: "fiction"), name: "Fiction"),
                GenreCrumb(genreId: GenreId(value: "fantasy"), name: "Fantasy")
            ],
            subGenres: [],
            hasSubs: false,
            includeSubGenres: false,
            stats: FacetStats(bookCount: 2, totalDurationMs: 100_000),
            books: []
        )

        let snapshot = GenrePageSnapshot.from(state, fallbackName: Self.fallback)

        #expect(snapshot.hasSubs == false)
        #expect(snapshot.subGenres.isEmpty)
        #expect(snapshot.breadcrumb.map(\.name) == ["Fiction", "Fantasy"])
        #expect(snapshot.blurb == nil)
    }
}
