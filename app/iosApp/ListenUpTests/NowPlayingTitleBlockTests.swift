import Foundation
import Testing
@testable import ListenUp

@Suite("Now Playing's title block words")
struct NowPlayingTitleBlockTests {
    @Test("the counter and the chapter's own title, inline")
    func counter() {
        #expect(NowPlayingTitleBlock.chapterLine(
            number: 6, total: 75, title: "Bridge Four", showsCounter: true, isWholeBook: false)
            == String(format: String(localized: "player.chapter_of"), "6", "75") + " · Bridge Four")
    }

    @Test("a generic title stands alone; a chapterless book reads Whole Book")
    func noCounter() {
        #expect(NowPlayingTitleBlock.chapterLine(
            number: 16, total: 75, title: "Chapter 14", showsCounter: false, isWholeBook: false) == "Chapter 14")
        #expect(NowPlayingTitleBlock.chapterLine(number: 1, total: 1, title: "", showsCounter: false, isWholeBook: true)
            == String(localized: "player.np_whole_book"))
    }

    @Test("the phone's chapter line is the chapter's own title, with no counter")
    func phoneChapterLine() {
        #expect(
            NowPlayingTitleBlock.phoneChapterLine(number: 6, title: "Bridge Four", isWholeBook: false) == "Bridge Four")
        #expect(NowPlayingTitleBlock.phoneChapterLine(number: 6, title: "", isWholeBook: false)
            == String(format: String(localized: "player.chapter_number"), 6))
        #expect(NowPlayingTitleBlock.phoneChapterLine(number: 1, title: "", isWholeBook: true)
            == String(localized: "player.np_whole_book"))
    }

    @Test("more than three people fold to the lead and a count")
    func fold() {
        let people = (1...5).map { ContributorNavRef(id: "n\($0)", name: "Narrator \($0)") }
        #expect(NowPlayingTitleBlock.folded(people)
            == String(format: String(localized: "player.np_byline_fold"), "Narrator 1", 4))
        #expect(NowPlayingTitleBlock.folded(Array(people.prefix(3))) == nil)
    }

    @Test("the folded menu names each person with their books in your library; none says nothing")
    func menuRows() {
        let people = [
            ContributorNavRef(id: "n1", name: "Michael Kramer", bookCount: 14),
            ContributorNavRef(id: "n2", name: "Kate Reading", bookCount: 1),
            ContributorNavRef(id: "n3", name: "New Narrator", bookCount: 0)
        ]
        #expect(NowPlayingTitleBlock.menuRows(people) == [
            BylineMenuRow(id: "n1", name: "Michael Kramer", books: "14 books"),
            BylineMenuRow(id: "n2", name: "Kate Reading", books: "1 book"),
            BylineMenuRow(id: "n3", name: "New Narrator", books: nil)
        ])
    }

    @Test("the series line names the book's place in it, or the series alone")
    func seriesLine() {
        #expect(NowPlayingTitleBlock.seriesLine(
            SeriesNavRef(id: "s1", name: "The Stormlight Archive", sequenceLabel: "1"))
            == "The Stormlight Archive · Book 1")
        #expect(NowPlayingTitleBlock.seriesLine(SeriesNavRef(id: "s1", name: "Discworld", sequenceLabel: nil))
            == "Discworld")
    }

    @Test("the menu's section names the role")
    func menuHeader() {
        #expect(NowPlayingTitleBlock.menuHeader(isAuthors: true) == String(localized: "player.sheet_authors"))
        #expect(NowPlayingTitleBlock.menuHeader(isAuthors: false) == String(localized: "player.sheet_narrators"))
    }
}
