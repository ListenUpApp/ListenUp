import Testing
import SwiftUI
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the facet-browse screen's observer boundary:
/// `FacetBooksSnapshot.from(_:fallbackName:)` flattening `BrowseFacetUiState` into the native
/// value types `FacetBooksView` renders. Mirrors `GenrePageSnapshotTests` — hand-built Kotlin
/// DTOs, no observer, no flow.
@Suite("FacetBooksSnapshot")
struct FacetBooksSnapshotTests {
    private static let fallback = "Atmospheric"

    @Test func loadingUsesTheRouteSuppliedFallbackName() {
        let snapshot = FacetBooksSnapshot.from(BrowseFacetUiStateLoading.shared, fallbackName: Self.fallback)
        #expect(snapshot.phase == .loading)
        #expect(snapshot.facetName == Self.fallback)
    }

    @Test func notFoundUsesTheRouteSuppliedFallbackName() {
        let snapshot = FacetBooksSnapshot.from(
            BrowseFacetUiStateNotFound(kind: .Tag),
            fallbackName: Self.fallback
        )
        #expect(snapshot.phase == .notFound)
        #expect(snapshot.facetName == Self.fallback)
    }

    /// The identity tile's hue/icon come from the same shared `FacetIdentity.hue(name:)`/
    /// `.icon(name:)` derivation the genre destination page uses — pinned here to prove a tag/mood
    /// snapshot resolves the SAME visual identity `GenrePageSnapshot` would for the same name.
    @Test func readyDerivesTheIdentityTileFromTheSharedFacetIdentity() {
        let state = BrowseFacetUiStateReady(
            kind: .Mood,
            facetName: "Atmospheric",
            books: [],
            bookCount: 12,
            totalDurationMs: 3_600_000
        )

        let snapshot = FacetBooksSnapshot.from(state, fallbackName: Self.fallback)

        #expect(snapshot.phase == .ready)
        #expect(snapshot.facetName == "Atmospheric")
        #expect(snapshot.totalDurationMs == 3_600_000)
        #expect(snapshot.books.isEmpty)
        #expect(snapshot.symbolName == sfSymbol(for: FacetIdentity.shared.icon(name: "Atmospheric")))

        // `hue` has no public component accessor; resolving it against a fixed environment is the
        // closest thing to a round-trip check that the derived hex string was parsed (mirrors
        // `FacetIdentityTests.hueColorParsesAHexStringWithoutCrashing`).
        let resolved = snapshot.hue.resolve(in: EnvironmentValues())
        #expect(resolved.opacity > 0)
    }
}
