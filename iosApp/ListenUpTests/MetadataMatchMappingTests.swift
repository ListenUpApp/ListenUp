import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the metadata-match observer's seams: search-hit flattening, the
/// chapter-suggestion projection (all three branches), and the preview field/selection mapping
/// (field filtering, selection counts, contributor/genre membership). These exercise
/// `MetadataMatchMapping` directly with hand-built Kotlin DTOs — no observer, no flow.
struct MetadataMatchMappingTests {
    // MARK: - Duration

    @Test func durationFormatsHoursAndMinutes() {
        #expect(MetadataDuration.format(minutes: 1223) == "20h 23m")
        #expect(MetadataDuration.format(minutes: 45) == "45m")
        #expect(MetadataDuration.format(minutes: 60) == "1h 0m")
    }

    // MARK: - Search result item

    @Test func resultItemFlattensContributors() {
        let book = makeBook(
            asin: "B01",
            title: "The Primal Hunter 9",
            runtime: 1223,
            authors: [ref("Zogarth")],
            narrators: [ref("Travis Baldree")]
        )
        let item = MetadataMatchMapping.resultItem(from: book)

        #expect(item.id == "B01")
        #expect(item.title == "The Primal Hunter 9")
        #expect(item.authors == "Zogarth")
        #expect(item.narrators == "Travis Baldree")
        #expect(item.subtitleLine == "Zogarth · Travis Baldree")
        #expect(item.runtimeMinutes == 1223)
    }

    @Test func resultItemSubtitleOmitsEmptySegments() {
        let book = makeBook(asin: "B02", title: "Solo", authors: [ref("A. Author")], narrators: [])
        let item = MetadataMatchMapping.resultItem(from: book)
        #expect(item.subtitleLine == "A. Author")
    }

    // MARK: - Chapter suggestion

    @Test func chapterStateUnavailableMapsToUnavailable() {
        let state = MetadataMatchMapping.chapterState(from: ChapterSuggestionUnavailable.shared)
        #expect(state == .unavailable)
    }

    @Test func chapterStateMismatchCarriesCounts() {
        let mismatch = ChapterSuggestionCountMismatch(localCount: 11, audibleCount: 80)
        let state = MetadataMatchMapping.chapterState(from: mismatch)
        #expect(state == .mismatch(localCount: 11, audibleCount: 80))
    }

    @Test func chapterStateAvailableProjectsRowsAndSelection() {
        let rows = [
            ChapterNameRow(ordinal: 0, currentName: "Chapter 1", suggestedName: "Opening Credits"),
            ChapterNameRow(ordinal: 1, currentName: "Chapter 2", suggestedName: "Previously…")
        ]
        let available = ChapterSuggestionAvailable(
            rows: rows,
            selectedOrdinals: Set([Int32(0)]),
            isApplying: false,
            applyError: nil
        )
        guard case .available(let result) = MetadataMatchMapping.chapterState(from: available) else {
            Issue.record("expected .available")
            return
        }
        #expect(result.totalCount == 2)
        #expect(result.selectedCount == 1)
        #expect(result.rows[0].isSelected == true)
        #expect(result.rows[1].isSelected == false)
        #expect(result.rows[0].suggestedName == "Opening Credits")
        #expect(result.allSelected == false)
    }

    // MARK: - Preview field mapping

    @Test func previewKeepsOnlyPopulatedScalarFields() {
        let book = makeBook(
            asin: "B10",
            title: "Title Here",
            subtitle: nil, // absent → no subtitle row
            publisher: "Aethon Audio",
            releaseDate: nil, // absent → no release-date row
            language: "English"
        )
        let preview = MetadataMatchMapping.preview(from: ready(book: book), match: book)

        #expect(preview.identityFields.map(\.field) == [.title])
        #expect(preview.detailFields.map(\.field) == [.publisher, .language])
        #expect(preview.descriptionField == nil)
    }

    @Test func previewMapsContributorAndGenreMembership() {
        let book = makeBook(
            asin: "B11",
            title: "Title",
            authors: [ref("Zogarth", asin: "AUTH1")],
            narrators: [ref("Travis Baldree", asin: "NARR1")],
            genres: ["Fantasy", "Epic"]
        )
        let selections = makeSelections(
            authors: ["AUTH1"],
            narrators: [], // narrator deselected
            genres: ["Fantasy"]
        )
        let preview = MetadataMatchMapping.preview(from: ready(book: book, selections: selections), match: book)

        #expect(preview.authors.first?.isSelected == true)
        #expect(preview.narrators.first?.isSelected == false)
        #expect(preview.genres.first { $0.label == "Fantasy" }?.isSelected == true)
        #expect(preview.genres.first { $0.label == "Epic" }?.isSelected == false)
    }

    @Test func previewSelectionCountReflectsToggledFields() {
        // cover + title + 1 author group + 1 genre group selected = 4 of (cover+title+author+genre)=4
        let book = makeBook(
            asin: "B12",
            title: "Title",
            authors: [ref("Z", asin: "A1")],
            genres: ["Fantasy"]
        )
        let allOn = makeSelections(
            cover: true, title: true,
            authors: ["A1"], genres: ["Fantasy"]
        )
        let preview = MetadataMatchMapping.preview(from: ready(book: book, selections: allOn), match: book)
        #expect(preview.totalCount == preview.selectedCount)
        #expect(preview.selectedCount == 4)

        // Deselect the cover and the author group → 2 of 4.
        let some = makeSelections(cover: false, title: true, authors: [], genres: ["Fantasy"])
        let preview2 = MetadataMatchMapping.preview(from: ready(book: book, selections: some), match: book)
        #expect(preview2.selectedCount == 2)
        #expect(preview2.totalCount == 4)
    }

    // MARK: - Provenance

    @Test func previewCarriesFieldSourceCoverAndContributingProvenance() {
        let book = makeBook(
            asin: "B20",
            title: "Title",
            description: "A description.",
            authors: [ref("Zogarth", asin: "AUTH1")],
            genres: ["Fantasy"]
        )
        let state = ready(
            book: book,
            fallbackSources: [.description: "Audnexus", .authors: "iTunes", .genres: "Audnexus"],
            contributingSources: ["Audible", "Audnexus", "iTunes"]
        )
        let preview = MetadataMatchMapping.preview(from: state, match: book)

        // Per-field fallback labels flow to the matching row structs; unmapped fields stay nil.
        #expect(preview.identityFields.first { $0.field == .title }?.sourceLabel == nil)
        #expect(preview.descriptionField?.sourceLabel == "Audnexus")
        #expect(preview.authors.first?.sourceLabel == "iTunes")
        #expect(preview.genres.first?.sourceLabel == "Audnexus")

        // Footer provenance copies through verbatim.
        #expect(preview.contributingSources == ["Audible", "Audnexus", "iTunes"])
    }

    @Test func previewMapsEveryCoverCandidateWithItsOwnHonestLabel() {
        let book = makeBook(asin: "B22", title: "Title")
        let state = ready(
            book: book,
            coverEntries: [
                CoverEntry(url: "https://cdn/itunes.jpg", label: "iTunes HD", resolution: "2400×2400"),
                CoverEntry(url: "https://cdn/audible.jpg", label: "Audible", resolution: nil)
            ]
        )
        let preview = MetadataMatchMapping.preview(from: state, match: book)

        // Every candidate is offered (a list, not one), each labelled by its OWN source — not a
        // single blanket "from Audible" over an iTunes image.
        #expect(preview.coverOptions.count == 2)
        #expect(preview.coverOptions.map(\.label) == ["iTunes HD", "Audible"])
        #expect(preview.coverOptions.first?.resolution == "2400×2400")
        #expect(preview.coverOptions.first?.url == "https://cdn/itunes.jpg")
    }

    @Test func previewProvenanceDefaultsAreEmpty() {
        let book = makeBook(asin: "B21", title: "Title", description: "Desc.")
        let preview = MetadataMatchMapping.preview(from: ready(book: book), match: book)

        #expect(preview.descriptionField?.sourceLabel == nil)
        #expect(preview.coverOptions.isEmpty)
        #expect(preview.contributingSources.isEmpty)
    }

    // MARK: - Fixtures

    private func ref(_ name: String, asin: String? = nil) -> MetadataContributorRef {
        MetadataContributorRef(asin: asin, name: name)
    }

    private func makeBook(
        asin: String,
        title: String,
        subtitle: String? = nil,
        description: String? = nil,
        publisher: String? = nil,
        releaseDate: String? = nil,
        runtime: Int? = nil,
        language: String? = nil,
        authors: [MetadataContributorRef] = [],
        narrators: [MetadataContributorRef] = [],
        series: [MetadataSeriesRef] = [],
        genres: [String] = [],
        moods: [String] = [],
        tags: [String] = [],
        coverUrl: String? = nil
    ) -> MetadataBook {
        MetadataBook(
            asin: asin,
            title: title,
            subtitle: subtitle,
            description: description,
            publisher: publisher,
            releaseDate: releaseDate,
            runtimeMinutes: runtime.map { Int32($0) },
            language: language,
            authors: authors,
            narrators: narrators,
            series: series,
            genres: genres,
            moods: moods,
            tags: tags,
            coverUrl: coverUrl,
            coverUrlMaxSize: nil,
            // Provenance is consumed off the resolved `PreviewLoadState.Ready`, not the raw book.
            matchProvenance: nil
        )
    }

    private func makeSelections(
        cover: Bool = true,
        title: Bool = true,
        authors: Set<String> = [],
        narrators: Set<String> = [],
        series: Set<String> = [],
        genres: Set<String> = [],
        moods: Set<String> = [],
        tags: Set<String> = []
    ) -> MetadataSelections {
        MetadataSelections(
            cover: cover,
            title: title,
            subtitle: true,
            description: true,
            publisher: true,
            releaseDate: true,
            language: true,
            selectedAuthors: authors,
            selectedNarrators: narrators,
            selectedSeries: series,
            selectedGenres: genres,
            selectedMoods: moods,
            selectedTags: tags
        )
    }

    private func ready(
        book: MetadataBook,
        selections: MetadataSelections? = nil,
        fallbackSources: [BookField: String] = [:],
        coverEntries: [CoverEntry] = [],
        coverSourceLabel: String? = nil,
        coverResolution: String? = nil,
        contributingSources: [String] = []
    ) -> PreviewLoadStateReady {
        PreviewLoadStateReady(
            preview: book,
            selections: selections ?? makeSelections(),
            coverEntries: coverEntries,
            selectedCoverUrl: nil,
            isApplying: false,
            applyError: nil,
            previewNotFound: false,
            chapterSuggestion: ChapterSuggestionUnavailable.shared,
            genreCandidates: book.genres,
            moodCandidates: book.moods,
            tagCandidates: book.tags,
            fallbackSources: fallbackSources,
            coverSourceLabel: coverSourceLabel,
            coverResolution: coverResolution,
            contributingSources: contributingSources
        )
    }
}
