import Foundation
import Testing
import Shared
@testable import ListenUp

/// The native projections of Book Detail's reading state. Only top-level Kotlin data classes can be built from Swift;
/// `ReadingStage`, `PrimaryAction` and `BookDetailSection` are nested objects, covered by the exhaustive `switch`es
/// compiling and by the Kotlin specs.
@Suite("Book Detail reading projections")
struct BookDetailReadingModelsTests {

    @Test func currentChapterProjectsEveryField() {
        let projected = BookCurrentChapter(
            CurrentChapter(index: 5, number: 6, title: "Bridge Four", startMs: 9_000, elapsedInChapterMs: 720_000,
                           isInPlayer: false))
        #expect(projected == BookCurrentChapter(
            index: 5, number: 6, title: "Bridge Four", startMs: 9_000, elapsedInChapterMs: 720_000))
    }

    @Test func finishedSpanKeepsAMissingStart() {
        let projected = BookFinishedSpan(FinishedSpan(startedAtMs: nil, finishedAtMs: 42))
        #expect(projected.startedAtMs == nil)
        #expect(projected.finishedAtMs == 42)
    }

    @Test func socialLineFromSummary() {
        let summary = ReadersSummary(
            listening: [ReaderRef(userId: "m", displayName: "Maya")],
            finished: [ReaderRef(userId: "t", displayName: "Tom")],
            avatars: [ReaderRef(userId: "m", displayName: "Maya"), ReaderRef(userId: "t", displayName: "Tom")],
            othersCount: 0)
        let line = ReadersSocialLine(summary)
        #expect(line?.listening == ["Maya"])
        #expect(line?.finished == ["Tom"])
        #expect(line?.avatarUserIds == ["m", "t"])
    }

    @Test func socialLineNamesReadersByFirstName() {
        let maya = ReaderRef(userId: "m", displayName: "Maya Chen")
        let tom = ReaderRef(userId: "t", displayName: "Tom Okafor")
        let line = ReadersSocialLine(ReadersSummary(listening: [maya], finished: [tom], avatars: [maya, tom], othersCount: 0))
        #expect(line?.listening == ["Maya"])
        #expect(line?.finished == ["Tom"])
        // The avatars keep the full name: it is their initials.
        #expect(line?.avatarNames == ["Maya Chen", "Tom Okafor"])
    }

    @Test func noOtherReadersHidesTheLine() {
        let summary = ReadersSummary(listening: [], finished: [], avatars: [], othersCount: 0)
        #expect(ReadersSocialLine(summary) == nil)
    }

    @Test func chapterRowCarriesItsStart() {
        let row = BookChapterRow(ChapterUiModel(
            id: "c", title: "T", duration: "1m", imageUrl: nil, isCurrent: false, startMs: 1_234, durationMs: 60_000))
        #expect(row.startMs == 1_234)
    }
}
