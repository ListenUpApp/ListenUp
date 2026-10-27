import CoreFoundation
import Testing
@testable import ListenUp

/// The iPad main column walks the visit's stage order a row at a time, pairing Ratings with Chapters only while in
/// progress on a wide main column (spec §3.2.4); a held book keeps About, Chapters and Details (§3.2.3).
@Suite("Book Detail iPad rows")
struct BookDetailIPadRowsTests {
    private let inProgress: [BookDetailSectionKind] = [
        .chapters, .about, .readers, .ratings, .hardcover, .details
    ]

    @Test func inProgressOnAWideMainColumnPairsChaptersWithRatings() {
        let rows = BookDetailIPadRows.rows(order: inProgress, stage: .inProgress, mainWidth: 760)
        #expect(rows.first == [.chapters, .ratings])
        #expect(rows.dropFirst().flatMap { $0 } == [.about, .readers, .hardcover, .details])
    }

    @Test func aNarrowMainColumnIsOneColumn() {
        let rows = BookDetailIPadRows.rows(order: inProgress, stage: .inProgress, mainWidth: 500)
        #expect(rows.allSatisfy { $0.count == 1 })
        #expect(rows.flatMap { $0 } == inProgress)
    }

    @Test func notStartedFollowsTheOrderUnpaired() {
        let order: [BookDetailSectionKind] = [.about, .ratings, .hardcover, .readers, .chapters, .details]
        #expect(BookDetailIPadRows.rows(order: order, stage: .notStarted, mainWidth: 760).flatMap { $0 } == order)
    }

    @Test func aHeldBookKeepsAboutChaptersAndDetailsInOrder() {
        #expect(BookDetailView.shown(inProgress, layout: .triage) == [.chapters, .about, .details])
        #expect(BookDetailView.shown(inProgress, layout: .full) == inProgress)
    }
}
