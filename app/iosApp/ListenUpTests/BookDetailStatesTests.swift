import Foundation
import Testing
@testable import ListenUp

/// Book Detail's states on iOS (spec §3.2.3): the scan warning's words, the offline reason, and the finished reader's
/// "How Was It?".
@Suite("Book Detail states")
struct BookDetailStatesTests {
    @Test func theScanBannerSpeaksInTheSingular() {
        #expect(ScanWarningBanner.message == "The scanner flagged this book’s files.")
        #expect(ScanWarningBanner.review == "Review")
    }

    @Test func theOfflineReasonSaysWhyNothingPlays() {
        #expect(OfflineReasonView.message ==
            "Can’t reach your server. This book isn’t downloaded, so it can’t play right now.")
    }

    @Test func aFinishedReaderIsAskedHowItWas() {
        #expect(BookRatingSection.heading(isFinished: true) == "How Was It?")
        #expect(BookRatingSection.heading(isFinished: false) == String(localized: "book.detail_rating_yours"))
        #expect(BookRatingSection.addNoteTitle(isFinished: true, hasNote: false) == "Add a Note for Your Listeners")
        #expect(BookRatingSection.addNoteTitle(isFinished: true, hasNote: true) == "Edit Note")
        #expect(BookRatingSection.addNoteTitle(isFinished: false, hasNote: false) == "Add a Note")
    }
}
