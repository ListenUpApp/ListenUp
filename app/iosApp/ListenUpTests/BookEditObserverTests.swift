import Testing
@testable import ListenUp

/// Pure-seam coverage for the book edit screen.
///
/// The sealed-state → observer flatten can't be exercised here: SKIE bridges
/// `BookEditUiState` as a value type whose nested `Editable*` lists aren't
/// ergonomically constructible from Swift, so the `apply(_:)` mapping lands at the
/// green-build pass (the app target proves it compiles). What *is* pure and
/// constructible is the series-chip label formatting, so that seam is pinned here.
@Suite("BookEditFormatting")
struct BookEditObserverTests {
    @Test func seriesLabelWithSequenceJoinsWithMiddot() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: "1") == "Screwtape · 1")
    }

    @Test func seriesLabelWithoutSequenceIsNameOnly() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: nil) == "Screwtape")
    }

    @Test func seriesLabelTreatsBlankSequenceAsAbsent() {
        #expect(BookEditFormatting.seriesLabel(name: "Screwtape", sequence: "   ") == "Screwtape")
    }

    @Test func seriesLabelKeepsDecimalSequence() {
        #expect(BookEditFormatting.seriesLabel(name: "Mistborn", sequence: "3.5") == "Mistborn · 3.5")
    }

    @Test func tagLabelTitleCasesHyphenatedSlug() {
        #expect(BookEditFormatting.tagLabel(slug: "found-family") == "Found Family")
    }

    @Test func tagLabelHandlesSingleWord() {
        #expect(BookEditFormatting.tagLabel(slug: "christian") == "Christian")
    }

    @Test func tagLabelDropsEmptySegments() {
        #expect(BookEditFormatting.tagLabel(slug: "slow--burn") == "Slow Burn")
    }

    // MARK: - EditableRelation projections (which field becomes the id vs the label)

    @Test func contributorRelationKeysByNameAndLabelsByName() {
        let row = EditableRelation.contributor(name: "Patrick Rothfuss")
        #expect(row.id == "Patrick Rothfuss")
        #expect(row.label == "Patrick Rothfuss")
    }

    @Test func seriesRelationKeysByNameAndFoldsSequenceIntoLabel() {
        let row = EditableRelation.series(name: "Mistborn", sequence: "3.5")
        #expect(row.id == "Mistborn")
        #expect(row.label == "Mistborn · 3.5")
    }

    @Test func genreRelationKeysByIdAndLabelsByName() {
        let row = EditableRelation.genre(id: "genre-7", name: "Epic Fantasy")
        #expect(row.id == "genre-7")
        #expect(row.label == "Epic Fantasy")
    }

    @Test func tagRelationKeysByIdAndTitleCasesSlug() {
        let row = EditableRelation.tag(id: "tag-42", slug: "found-family")
        #expect(row.id == "tag-42")
        #expect(row.label == "Found Family")
    }

    @Test func moodRelationKeysByIdAndTitleCasesSlug() {
        let row = EditableRelation.mood(id: "mood-9", slug: "slow-burn")
        #expect(row.id == "mood-9")
        #expect(row.label == "Slow Burn")
    }

    // MARK: - Add-picker result subtitles

    @Test func bookCountSubtitleIsNilForZero() {
        #expect(BookEditFormatting.bookCountSubtitle(0) == nil)
    }

    @Test func bookCountSubtitleSingularForOne() {
        #expect(BookEditFormatting.bookCountSubtitle(1) == "1 book")
    }

    @Test func bookCountSubtitlePluralForMany() {
        #expect(BookEditFormatting.bookCountSubtitle(7) == "7 books")
    }

    @Test func genreParentPathDropsLeafAndTitleCases() {
        #expect(BookEditFormatting.genreParentPath("/fiction/fantasy/epic-fantasy") == "Fiction > Fantasy")
    }

    @Test func genreParentPathIsNilForTopLevel() {
        #expect(BookEditFormatting.genreParentPath("/fiction") == nil)
    }

    @Test func genreParentPathHandlesNoLeadingSlash() {
        #expect(BookEditFormatting.genreParentPath("fiction/fantasy") == "Fiction")
    }
}
