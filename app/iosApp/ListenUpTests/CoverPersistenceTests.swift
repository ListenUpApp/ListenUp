import Testing
@testable import ListenUp

/// Pins the de-duplication in front of `ensureBookCoverCached`: each cover cell used to ask on every
/// appearance, costing a coroutine, a disk check, a database write and a log line each time.
@MainActor
@Suite("CoverPersistence")
struct CoverPersistenceTests {
    private final class Recorder {
        var persisted: [String] = []
    }

    @Test func repeatedAppearancesPersistOnce() {
        let recorder = Recorder()
        let persistence = CoverPersistence { recorder.persisted.append($0) }
        for _ in 0..<5 {
            persistence.ensureCached(bookId: "b1", coverPath: "/c.jpg", coverHash: "h1")
        }
        #expect(recorder.persisted == ["b1"])
    }

    /// A re-scraped cover has a new hash, so it is a new version to persist.
    @Test func aNewCoverVersionPersistsAgain() {
        let recorder = Recorder()
        let persistence = CoverPersistence { recorder.persisted.append($0) }
        persistence.ensureCached(bookId: "b1", coverPath: "/c.jpg", coverHash: "h1")
        persistence.ensureCached(bookId: "b1", coverPath: "/c.jpg", coverHash: "h2")
        #expect(recorder.persisted == ["b1", "b1"])
    }

    @Test func distinctBooksEachPersist() {
        let recorder = Recorder()
        let persistence = CoverPersistence { recorder.persisted.append($0) }
        persistence.ensureCached(bookId: "b1", coverPath: nil, coverHash: nil)
        persistence.ensureCached(bookId: "b2", coverPath: nil, coverHash: nil)
        #expect(recorder.persisted == ["b1", "b2"])
    }

    /// An unversioned cover that is already on disk needs nothing.
    @Test func anUnversionedDownloadedCoverIsNotPersisted() {
        let recorder = Recorder()
        let persistence = CoverPersistence { recorder.persisted.append($0) }
        persistence.ensureCached(bookId: "b1", coverPath: "/c.jpg", coverHash: nil)
        #expect(recorder.persisted.isEmpty)
    }
}
