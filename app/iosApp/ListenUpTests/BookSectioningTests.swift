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
            coverPath: nil
        )
    }

    @Test func groupsByUppercasedFirstLetter() {
        let sections = bookSections(from: [row("1", "Alpha"), row("2", "apple"), row("3", "Beta")], ignoreArticles: false)
        #expect(sections.map { $0.letter } == ["A", "B"])
        #expect(sections.first?.books.count == 2)
    }

    @Test func nonLetterTitlesBucketIntoHashAndSortFirst() {
        let sections = bookSections(from: [row("1", "Zed"), row("2", "1984"), row("3", "Mid")], ignoreArticles: false)
        #expect(sections.map { $0.letter } == ["#", "M", "Z"])
    }

    @Test func preservesIncomingOrderWithinASection() {
        let sections = bookSections(from: [row("1", "Apple"), row("2", "Anchor")], ignoreArticles: false)
        #expect(sections.first?.books.map { $0.id } == ["1", "2"])
    }

    @Test func emptyInputYieldsNoSections() {
        #expect(bookSections(from: [], ignoreArticles: false).isEmpty)
    }

    @Test func ignoresLeadingArticlesWhenEnabled() {
        let sections = bookSections(
            from: [row("1", "The Hobbit"), row("2", "A Wrinkle in Time"), row("3", "An Apple"), row("4", "Zebra")],
            ignoreArticles: true
        )
        // "The Hobbit"→H, "A Wrinkle"→W, "An Apple"→A, "Zebra"→Z
        #expect(sections.map { $0.letter } == ["A", "H", "W", "Z"])
    }

    @Test func keepsLeadingArticlesWhenDisabled() {
        let sections = bookSections(
            from: [row("1", "The Hobbit"), row("2", "A Wrinkle in Time"), row("3", "Zebra")],
            ignoreArticles: false
        )
        // No stripping: "The Hobbit"→T, "A Wrinkle"→A, "Zebra"→Z
        #expect(sections.map { $0.letter } == ["A", "T", "Z"])
    }

    @Test func articleNotFollowedByWhitespaceIsKept() {
        // "A.I." and "Anchor" start with an article substring but no whitespace follows → not
        // stripped, so both keep their real first letter "A".
        let sections = bookSections(from: [row("1", "A.I."), row("2", "Anchor")], ignoreArticles: true)
        #expect(sections.map { $0.letter } == ["A"])
        #expect(sections.first?.books.count == 2)
    }
}
