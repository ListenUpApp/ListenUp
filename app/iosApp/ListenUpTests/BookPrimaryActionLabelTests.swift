import Foundation
import Testing
@testable import ListenUp

@MainActor
@Suite("Book Detail primary label")
struct BookPrimaryActionLabelTests {
    @Test func eachStageNamesItsAction() {
        #expect(BookPrimaryActionLabel(kind: .play, isPreparing: false).key == "book.detail_play_ios")
        #expect(BookPrimaryActionLabel(kind: .resume, isPreparing: false).key == "book.detail_resume_ios")
        #expect(BookPrimaryActionLabel(kind: .listenAgain, isPreparing: false).key == "book.detail_listen_again_ios")
        #expect(BookPrimaryActionLabel(kind: .unavailableOffline, isPreparing: false).key
            == "book.detail_unavailable_offline")
    }

    @Test func titlesAreLocalized() {
        #expect(BookPrimaryActionLabel(kind: .listenAgain, isPreparing: false).title
            == String(localized: "book.detail_listen_again_ios"))
        #expect(BookPrimaryActionLabel(kind: .resume, isPreparing: false).title
            == String(localized: "book.detail_resume_ios"))
    }

    @Test func preparingIsBusyNotRelabelled() {
        let label = BookPrimaryActionLabel(kind: .resume, isPreparing: true)
        #expect(label.isBusy)
        #expect(label.key == "book.detail_resume_ios")
    }

    @Test func onlyUnavailableOfflineIsDisabled() {
        #expect(BookPrimaryActionLabel(kind: .play, isPreparing: false).isEnabled)
        #expect(BookPrimaryActionLabel(kind: .resume, isPreparing: false).isEnabled)
        #expect(BookPrimaryActionLabel(kind: .listenAgain, isPreparing: false).isEnabled)
        #expect(!BookPrimaryActionLabel(kind: .unavailableOffline, isPreparing: false).isEnabled)
    }

    @Test func timeLeftUsesHoursAndMinutes() {
        let text = ResumeUnit.timeLeftText(remainingMs: Int64((40 * 60 + 31) * 60_000))
        #expect(text.contains("40"))
        #expect(text.contains("31"))
    }

    @Test func chapterReadsNumberAndTitle() {
        let chapter = BookCurrentChapter(index: 5, number: 6, title: "Bridge Four", startMs: 0, elapsedInChapterMs: 0)
        #expect(ResumeUnit.chapterText(chapter) == "Ch. 6 · Bridge Four")
    }

    @Test func finishedSpanNamesBothDaysOrJustTheFinish() {
        let span = BookFinishedSpan(startedAtMs: 1_770_854_400_000, finishedAtMs: 1_772_496_000_000)
        let both = ResumeUnit.finishedText(span)
        #expect(both.hasPrefix("Finished · "))
        #expect(both.contains(" – "))
        let one = ResumeUnit.finishedText(BookFinishedSpan(startedAtMs: nil, finishedAtMs: 1_772_496_000_000))
        #expect(one.hasPrefix("Finished · "))
        #expect(!one.contains(" – "))
    }
}
