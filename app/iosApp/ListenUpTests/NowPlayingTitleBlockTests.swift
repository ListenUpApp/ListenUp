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

    @Test("more than three people fold to the lead and a count")
    func fold() {
        let people = (1...5).map { ContributorNavRef(id: "n\($0)", name: "Narrator \($0)") }
        #expect(NowPlayingTitleBlock.folded(people)
            == String(format: String(localized: "player.np_byline_fold"), "Narrator 1", 4))
        #expect(NowPlayingTitleBlock.folded(Array(people.prefix(3))) == nil)
    }
}
