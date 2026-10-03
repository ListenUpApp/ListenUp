import SwiftUI
import Testing
@testable import ListenUp

/// Book Detail's Readers and Hardcover card, read the way VoiceOver and large text meet them (#1562).
@MainActor
@Suite("Book Detail Hardcover accessibility", .serialized)
struct BookDetailHardcoverAccessibilityTests {
    private let readers = [
        BookReaderRow(id: "u1", displayName: "Rig Reader", initials: "RR", isYou: true, progressPercent: nil,
                      lastFinished: Date(timeIntervalSince1970: 1_462_000_000), lastFinishedOnHardcover: true),
        BookReaderRow(id: "u2", displayName: "Marcus Lee", initials: "ML", isYou: false, progressPercent: 34,
                      lastFinished: nil),
        BookReaderRow(id: "u3", displayName: "Lena Ortiz", initials: "LO", isYou: false, progressPercent: nil,
                      lastFinished: Date(timeIntervalSince1970: 1_727_000_000), halfStars: 8,
                      note: "Better on a second listen.")
    ]

    private var linked: BookHardcoverPhase {
        .linked(BookHardcoverLinkedModel(
            title: "The Two Towers", byline: "J. R. R. Tolkien · 1954", chosenByYou: true,
            status: BookHardcoverObserver.status(for: .upToDate)
        ))
    }

    private func readersSection() async -> HostedView {
        await HostedView(NavigationStack { ScrollView { BookReadersSection(readers: readers).padding() } })
    }

    private func hardcoverCard(_ phase: BookHardcoverPhase) async -> HostedView {
        await HostedView(
            ScrollView {
                BookHardcoverSection(phase: phase, onFindMatch: {}, onRemoveMatch: {}, onSetSynced: { _ in },
                                     onConfirmKeepOff: { _ in })
                    .padding()
            }
            .background(Color(.systemBackground))
        )
    }

    // MARK: m5 — no "selected" on reader rows

    /// The combined row folded the trailing `checkmark` symbol's implicit Selected trait into a
    /// navigation row that has no selection.
    @Test func readerRowsAreNotSelected() async throws {
        let hosted = await readersSection()
        defer { hosted.close() }
        let rows = hosted.stops.filter { $0.label.contains("Rig Reader") || $0.label.contains("Lena Ortiz") }
        try #require(rows.count == 2, "\(hosted.tree)")
        for row in rows {
            #expect(!row.isSelected, "\(row)")
        }
    }

    // MARK: m6 — Readers is a heading

    @Test func readersIsAHeading() async throws {
        let hosted = await readersSection()
        defer { hosted.close() }
        let heading = try #require(hosted.stops(labelContaining: String(localized: "book.detail_readers")).first,
                                   "\(hosted.tree)")
        #expect(heading.isHeader, "\(heading)")
    }

    // MARK: M5 — names keep their room at accessibility sizes

    /// At AX5 the name shared a line with the stars and a body-sized glyph, and `lineLimit(1)` left "Rig Rea…"
    /// or nothing at all. HIG, Typography: let text wrap rather than truncate at large sizes.
    @Test func atAccessibilitySizesTheNameWrapsAndTheStarsGetTheirOwnLine() {
        let large = BookReadersSection.RowLayout(.accessibility5)
        #expect(large.starsBelowName)
        #expect(large.nameLineLimit == nil)
        #expect(!large.showsTrailingGlyph)

        let regular = BookReadersSection.RowLayout(.large)
        #expect(!regular.starsBelowName)
        #expect(regular.nameLineLimit == 1)
        #expect(regular.showsTrailingGlyph)
    }

    // MARK: m7 — 44-point targets

    @Test func theCardsActionsAreAtLeastFortyFourPointsTall() async throws {
        let hosted = await hardcoverCard(linked)
        defer { hosted.close() }
        for key in ["hardcover.book_row_change_match", "hardcover.match_remove"] {
            let button = try #require(hosted.stop(labelled: String(localized: String.LocalizationValue(key))
                .titleStyled), "\(hosted.tree)")
            #expect(button.frame.height >= TapTarget.minimum - 0.5, "\(button)")
        }
        let needsMatch = await hardcoverCard(.needsMatch)
        defer { needsMatch.close() }
        let find = try #require(needsMatch.stop(labelled: String(localized: "hardcover.find_on_hardcover").titleStyled),
                                "\(needsMatch.tree)")
        #expect(find.frame.height >= TapTarget.minimum - 0.5, "\(find)")
    }

    // MARK: m8 — the card's action labels clear 4.5:1

    /// Remove Match was system red on the card (3.20:1); Change Match the tint on its own tinted fill (3.27:1).
    @Test func theCardsActionLabelsAreReadable() async throws {
        let hosted = await hardcoverCard(linked)
        defer { hosted.close() }
        let drawn = DrawnContrast(hosted)
        for key in ["hardcover.book_row_change_match", "hardcover.match_remove"] {
            let button = try #require(hosted.stop(labelled: String(localized: String.LocalizationValue(key))
                .titleStyled), "\(hosted.tree)")
            let ink = drawn.strongestInk(in: button.frame)
            #expect(ink >= ContrastMinimum.text, "\(key) drawn at \(ink):1")
        }
    }

    /// The "Hardcover" source badge was secondary grey on its fill, 3.11:1.
    @Test func theSourceBadgeIsReadable() async throws {
        let hosted = await HostedView(
            SourceBadge(label: String(localized: "book.detail_readers_hardcover"))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color(.systemBackground))
        )
        defer { hosted.close() }
        let badge = try #require(hosted.stop(labelled: String(localized: "book.detail_readers_hardcover")),
                                 "\(hosted.tree)")
        let ink = DrawnContrast(hosted).strongestInk(in: badge.frame.insetBy(dx: 2, dy: 2))
        #expect(ink >= ContrastMinimum.text, "badge drawn at \(ink):1")
    }
}
