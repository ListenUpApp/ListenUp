import Testing
@testable import ListenUp

/// Pins the alphabet-section grouping that powers the Library books grid and its
/// scrubber. The regression guard: grouping/ordering runs over the native `BookRow`
/// value type (cheap Swift strings), not re-bridged Kotlin objects — and the `#`
/// bucket for non-letter titles sorts to the top.
@Suite("BookRow sectioning")
struct BookSectioningTests {
    private func row(_ id: String, _ title: String) -> BookRow {
        BookRow(
            id: id,
            title: title,
            authorNames: "Author",
            hasDocuments: false,
            coverPath: nil,
            coverBlurHash: nil
        )
    }

    @Test func groupsByUppercasedFirstLetter() {
        let sections = bookSections(from: [row("1", "Alpha"), row("2", "apple"), row("3", "Beta")])
        #expect(sections.map { $0.letter } == ["A", "B"])
        #expect(sections.first?.books.count == 2)
    }

    @Test func nonLetterTitlesBucketIntoHashAndSortFirst() {
        let sections = bookSections(from: [row("1", "Zed"), row("2", "1984"), row("3", "Mid")])
        #expect(sections.map { $0.letter } == ["#", "M", "Z"])
    }

    @Test func preservesIncomingOrderWithinASection() {
        let sections = bookSections(from: [row("1", "Apple"), row("2", "Anchor")])
        #expect(sections.first?.books.map { $0.id } == ["1", "2"])
    }

    @Test func emptyInputYieldsNoSections() {
        #expect(bookSections(from: []).isEmpty)
    }
}
