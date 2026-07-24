import Testing
@testable import ListenUp

/// Pins the native row models the Contributor/Series detail screens map Kotlin objects into,
/// so the detail `ForEach`s diff cheap Swift values instead of re-bridging across SKIE.
@Suite("Detail row models")
struct DetailRowModelsTests {
    @Test func bookRowCarriesDurationAndSequence() {
        let row = BookRow(
            id: "b1", title: "T", authorNames: "A",
            hasDocuments: false, coverPath: nil,
            duration: 3_600_000, sequence: "2"
        )
        #expect(row.duration == 3_600_000)
        #expect(row.sequence == "2")
    }

    @Test func bookRowDefaultsDurationAndSequenceForNonSeriesContexts() {
        let row = BookRow(
            id: "b1", title: "T", authorNames: "A",
            hasDocuments: false, coverPath: nil
        )
        #expect(row.duration == 0)
        #expect(row.sequence == nil)
    }

    @Test func roleSectionRowIsKeyedByRoleAndHoldsItsBooks() {
        let books = [
            BookRow(id: "1", title: "One", authorNames: "", hasDocuments: false, coverPath: nil),
            BookRow(id: "2", title: "Two", authorNames: "", hasDocuments: false, coverPath: nil)
        ]
        let section = RoleSectionRow(role: "author", displayName: "Author", books: books)
        #expect(section.id == "author")
        #expect(section.books.map(\.id) == ["1", "2"])
    }
}
