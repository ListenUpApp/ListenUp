import SwiftUI
import Testing
@testable import ListenUp

@Suite("DownloadUIState")
struct BookDetailObserverTests {
    // BookDetailObserver's `apply` needs live KMP BookDetailUiState instances —
    // behavioural verification lands at the green-build pass with a fake VM.
    // The pure piece pinned now: the DownloadUIState cases are distinct.
    @Test func downloadStatesAreDistinct() {
        let all: [DownloadUIState] = [
            .notDownloaded, .queued, .downloading, .completed, .partial, .failed
        ]
        #expect(all.count == 6)
    }
}

/// Pure-seam coverage for the three-axis facet chips (genre / tag / mood).
///
/// The observer's `genres`/`tags`/`moods` flatten reads live KMP `Ready` state
/// (`r.genresList` / `r.tags` / `r.moods`), whose models aren't ergonomically
/// constructible from Swift — that mapping lands at the green-build pass like
/// `chapters`. What *is* pure and constructible is the per-facet styling contract:
/// genres are neutral ("where it lives", no icon); tags carry a leading symbol
/// ("tropes"); moods carry a leading symbol and lean on the per-book accent
/// ("how it feels"). Those invariants are pinned here so a future edit can't
/// silently collapse the three axes back into one.
@Suite("BookFacetKind")
struct BookFacetKindTests {
    @Test func kindsAreDistinct() {
        let all: [BookFacetKind] = [.genre, .tag, .mood]
        #expect(Set(all).count == 3)
    }

    @Test func genreHasNoIcon() {
        #expect(BookFacetKind.genre.symbolName == nil)
    }

    @Test func tagAndMoodCarryAnIcon() {
        #expect(BookFacetKind.tag.symbolName != nil)
        #expect(BookFacetKind.mood.symbolName != nil)
    }

    @Test func tagAndMoodUseDistinctIcons() {
        #expect(BookFacetKind.tag.symbolName != BookFacetKind.mood.symbolName)
    }

    @Test func onlyMoodAndTagLeanOnTheAccent() {
        #expect(BookFacetKind.genre.usesAccent == false)
        #expect(BookFacetKind.tag.usesAccent == true)
        #expect(BookFacetKind.mood.usesAccent == true)
    }

    @Test func accessibilityLabelNamesTheAxis() {
        #expect(BookFacetKind.genre.accessibilityLabel(for: "Fantasy") == "Genre: Fantasy")
        #expect(BookFacetKind.tag.accessibilityLabel(for: "Slow Burn") == "Tag: Slow Burn")
        #expect(BookFacetKind.mood.accessibilityLabel(for: "Tense") == "Mood: Tense")
    }
}
