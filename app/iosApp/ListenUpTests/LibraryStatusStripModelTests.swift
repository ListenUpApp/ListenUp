import Testing
@preconcurrency import Shared
@testable import ListenUp

/// The Library's status strip on iOS: the shared decision mapped to Swift values and the board's words.
@Suite("Library status strip")
struct LibraryStatusStripModelTests {
    @Test func arrivingCarriesTheCountAndTheWords() {
        let model = LibraryStatusStripModel.from(LibraryStatusStripArriving(booksSoFar: 412))
        #expect(model == .arriving(booksSoFar: 412))
        #expect(model?.title == "Building Your Library")
        #expect(model?.detail == "412 books so far. Books appear here as they arrive; you can start listening now.")
        #expect(model?.fraction == nil)
    }

    @Test func oneBookIsSingular() {
        #expect(LibraryStatusStripModel.arriving(booksSoFar: 1).detail.hasPrefix("1 book so far."))
    }

    @Test func noneYetSaysOnlyThatBooksArrive() {
        #expect(LibraryStatusStripModel.arriving(booksSoFar: 0).detail == "Books appear here as they arrive.")
    }

    @Test func scanningNamesTheServerAndItsProgress() {
        let scan = ScanProgressState(
            phase: "analyzing", current: 412, total: 1280, added: 3, updated: 0, removed: 0,
            filesTotal: 0, books: 412, booksTotal: 1280, authors: 0, durationMs: 0,
            currentFile: nil, recentBooks: [], startedAtMs: 0
        )
        let model = LibraryStatusStripModel.from(LibraryStatusStripScanning(progress: scan))
        #expect(model?.title == "Scanning Your Server")
        #expect(model?.detail == "412 of 1,280 · 3 new")
        #expect(model?.fraction == 412.0 / 1280.0)
    }

    @Test func aScanWithNothingNewSaysOnlyHowFar() {
        #expect(LibraryStatusStripModel.scanning(done: 2, total: 9, added: 0, phase: "Analyzing").detail == "2 of 9")
    }

    @Test func aScanWithNoTotalYetNamesItsPhase() {
        let model = LibraryStatusStripModel.scanning(done: 0, total: 0, added: 0, phase: "Discovering files")
        #expect(model.detail == "Discovering files")
        #expect(model.fraction == nil)
    }

    @Test func hiddenIsNil() {
        #expect(LibraryStatusStripModel.from(LibraryStatusStripHidden.shared) == nil)
    }
}
