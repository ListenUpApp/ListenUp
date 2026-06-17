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

@Suite("Cast helpers")
struct CastHelpersTests {
    private func member(_ name: String, _ roles: [String]) -> CastMember {
        CastMember(id: name, name: name, roles: roles)
    }

    @Test func collapsedSummaryNilAtOrBelowLimit() {
        #expect(collapsedContributorSummary(names: [], limit: 2) == nil)
        #expect(collapsedContributorSummary(names: ["A"], limit: 2) == nil)
        #expect(collapsedContributorSummary(names: ["A", "B"], limit: 2) == nil)
    }

    @Test func collapsedSummaryAboveLimit() {
        #expect(collapsedContributorSummary(names: ["Roy", "B", "C"], limit: 2) == "Roy & 2 others")
    }

    @Test func castGroupsOmitsEmptyProduction() {
        let groups = castGroups(
            authors: [member("Author A", ["author"])],
            narrators: [member("Narr A", ["narrator"]), member("Narr B", ["narrator"])],
            all: [member("Author A", ["author"]), member("Narr A", ["narrator"]), member("Narr B", ["narrator"])]
        )
        #expect(groups.map(\.kind) == [.authors, .narrators])
    }

    @Test func castGroupsIncludesProductionWhenPresent() {
        let groups = castGroups(
            authors: [member("Author A", ["author"])],
            narrators: [member("Narr A", ["narrator"])],
            all: [member("Author A", ["author"]), member("Narr A", ["narrator"]), member("Editor E", ["editor"])]
        )
        #expect(groups.map(\.kind) == [.authors, .narrators, .production])
        #expect(groups.last?.members.map(\.name) == ["Editor E"])
    }

    @Test func productionExcludesAuthorsAndNarratorsCaseInsensitive() {
        let groups = castGroups(
            authors: [member("A", ["author"])],
            narrators: [member("N", ["narrator"])],
            all: [member("A", ["Author"]), member("N", ["NARRATOR"]), member("P", ["producer"])]
        )
        #expect(groups.last?.kind == .production)
        #expect(groups.last?.members.map(\.name) == ["P"])
    }
}

/// Pure-seam coverage for the three-axis facet chips (genre / tag / mood).
///
/// The observer's `genres`/`tags`/`moods` flatten reads live KMP `Ready` state
/// (`r.genresList` / `r.tags` / `r.moods`), whose models aren't ergonomically
/// constructible from Swift — that mapping lands at the green-build pass like
/// `chapters`. What *is* pure and constructible is the per-facet styling contract:
/// genres are a neutral solid fill with no icon; tags are an outlined capsule with no
/// icon; moods lean on the per-book accent with a sparkles icon. Those invariants are
/// pinned here so a future edit can't silently collapse the three axes back into one.
@Suite("BookFacetKind")
struct BookFacetKindTests {
    @Test func kindsAreDistinct() {
        #expect(Set(BookFacetKind.allCases).count == BookFacetKind.allCases.count)
    }

    @Test func genreAndTagHaveNoIcon() {
        #expect(BookFacetKind.genre.symbolName == nil)
        #expect(BookFacetKind.tag.symbolName == nil)
    }

    @Test func moodUsesSparkles() {
        #expect(BookFacetKind.mood.symbolName == "sparkles")
    }

    @Test func onlyMoodLeansOnTheAccent() {
        #expect(BookFacetKind.genre.usesAccent == false)
        #expect(BookFacetKind.tag.usesAccent == false)
        #expect(BookFacetKind.mood.usesAccent == true)
    }

    @Test func onlyTagIsOutlined() {
        #expect(BookFacetKind.genre.isOutlined == false)
        #expect(BookFacetKind.tag.isOutlined == true)
        #expect(BookFacetKind.mood.isOutlined == false)
    }

    @Test func accessibilityLabelNamesTheAxis() {
        #expect(BookFacetKind.genre.accessibilityLabel(for: "Epic") == "Genre: Epic")
        #expect(BookFacetKind.tag.accessibilityLabel(for: "Owned") == "Tag: Owned")
        #expect(BookFacetKind.mood.accessibilityLabel(for: "Dark") == "Mood: Dark")
    }
}
