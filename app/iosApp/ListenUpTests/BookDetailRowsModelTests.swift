import Testing
@testable import ListenUp

/// Book Detail's Details rows on iOS (spec §3.2.1 D31): the values as the board writes them, and which rows are links.
@Suite("Book Detail rows")
struct BookDetailRowsModelTests {
    @Test func valuesReadAsTheBoardWritesThem() {
        #expect(BookDetailRowValue.genre(name: "Epic fantasy", abridged: false).cell.value == "Epic fantasy · Unabridged")
        #expect(BookDetailRowValue.length(durationMs: 163_800_000, chapterCount: 75).cell.value
            == "\(BookDetailHero.durationText(ms: 163_800_000)) · 75 chapters")
        #expect(BookDetailRowValue.length(durationMs: 163_800_000, chapterCount: 1).cell.value
            == "\(BookDetailHero.durationText(ms: 163_800_000)) · 1 chapter")
        #expect(BookDetailRowValue.filesAndAudio(container: "M4B", bitrate: "64 kbps").cell.value == "M4B · 64 kbps")
        #expect(BookDetailRowValue.allCredits(count: 6).cell.value == "6")
    }

    @Test func theThreeLinkRowsAreLinksAndNothingElseIs() {
        #expect(BookDetailRowValue.allCredits(count: 6).cell.link == .credits)
        #expect(BookDetailRowValue.filesAndAudio(container: nil, bitrate: nil).cell.link == .filesAndAudio)
        #expect(BookDetailRowValue.maps(count: 1, onlyDocumentId: "d1", format: "PDF", totalBytes: 1).cell.link
            == .document("d1"))
        #expect(BookDetailRowValue.maps(count: 2, onlyDocumentId: nil, format: nil, totalBytes: 1).cell.link == .documents)
        #expect(BookDetailRowValue.isbn("978").cell.link == nil)
    }

    @Test func labelsAreTitleCaseOnIOS() {
        #expect(BookDetailRowValue.filesAndAudio(container: nil, bitrate: nil).cell.label == "Files & Audio")
        #expect(BookDetailRowValue.maps(count: 1, onlyDocumentId: "d", format: "PDF", totalBytes: 1).cell.label
            == "Maps & Illustrations")
    }
}
