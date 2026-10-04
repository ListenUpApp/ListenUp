import SwiftUI
import Testing
@testable import ListenUp

/// Settings → Hardcover, read the way VoiceOver and the largest text sizes meet it (#1562).
@MainActor
@Suite("Hardcover settings accessibility", .serialized, .flakyOnCI)
struct HardcoverSettingsAccessibilityTests {
    private static func books(_ count: Int) -> [HardcoverBookToMatchRow] {
        (1...count).map {
            HardcoverBookToMatchRow(id: "b\($0)", title: "Book \($0)", authorNames: "Author \($0)",
                                    coverPath: nil, coverHash: nil)
        }
    }

    private func connected(_ configure: (inout HardcoverConnectedModel) -> Void) -> some View {
        var model = HardcoverConnectedModel(
            username: "rigreader", since: Date(timeIntervalSince1970: 1_790_000_000), isDisconnecting: false
        )
        model.lastSyncedAt = Date().addingTimeInterval(-180)
        model.isMatchListKnown = true
        configure(&model)
        let fixed = model
        return NavigationStack {
            HardcoverConnectedPhase(
                model: fixed, onSyncNow: {}, onSetShareMode: { _ in }, onSendHistory: {}, onDismissHistory: {},
                onFindMatch: { _ in }, onDisconnect: {}
            )
        }
    }

    private var findLabel: String { String(localized: "hardcover.find_on_hardcover") }

    // MARK: M1 — "Connected"

    /// The word was system green on white, 2.22:1. HIG, Accessibility: text needs at least 4.5:1.
    @Test func theConnectedLineIsReadableText() async throws {
        let hosted = await HostedView(connected { _ in })
        defer { hosted.close() }
        let identity = try #require(hosted.stops(labelContaining: "rigreader").first, "\(hosted.tree)")
        // The text column only: the avatar circle to its left is the tint and fills every row.
        let column = CGRect(x: identity.frame.minX + 80, y: identity.frame.minY, width: identity.frame.width - 80,
                            height: identity.frame.height)
        let lines = DrawnContrast(hosted).textLines(in: column)
        // Name, Connected, Since.
        try #require(lines.count == 3, "lines: \(lines)")
        #expect(lines[1] >= ContrastMinimum.text, "Connected drawn at \(lines[1]):1")
    }

    // MARK: M2 — the quiet row's detail

    /// `.secondary` inside a Button label resolves to the tint at secondary opacity (2.19:1), not to grey.
    @Test func theEarlierBooksDetailIsGreyNotFadedTint() async throws {
        let hosted = await HostedView(connected { $0.history = .available(books: 75) })
        defer { hosted.close() }
        let row = try #require(
            hosted.stop(labelled: String(localized: "hardcover.history_row_send_label")), "\(hosted.tree)"
        )
        // Past the leading icon, so only the two lines of text are measured.
        let text = CGRect(x: row.frame.minX + 44, y: row.frame.minY, width: row.frame.width - 44,
                          height: row.frame.height)
        let lines = DrawnContrast(hosted).textLines(in: text)
        try #require(lines.count == 2, "lines: \(lines)")
        // System secondary label grey here is 3.44:1 (the systemic note, left to the system and to Increase
        // Contrast); the tint faded to secondary opacity was 2.20:1.
        #expect(lines[1] >= 3.0, "detail drawn at \(lines[1]):1")
    }

    // MARK: M3 — Needs a match is bounded

    @Test func aLongNeedsAMatchListShowsFiveThenShowAll() async throws {
        let hosted = await HostedView(
            connected { $0.booksToMatch = Self.books(28) },
            size: CGSize(width: 393, height: 4000)
        )
        defer { hosted.close() }
        let rows = hosted.stops(labelContaining: findLabel)
        #expect(rows.count == 5, "\(hosted.tree)")
        let showAll = String(format: String(localized: "hardcover.needs_match_show_all"), 28).titleStyled
        let button = try #require(hosted.stop(labelled: showAll), "\(hosted.tree)")
        #expect(button.isButton)
        // What ListenUp shares is reachable straight after the list, not 28 rows later.
        #expect(hosted.stops(labelContaining: String(localized: "hardcover.share_mode_label").titleStyled).count == 1)
    }

    @Test func aShortNeedsAMatchListShowsEveryBookAndNoShowAll() async throws {
        let hosted = await HostedView(
            connected { $0.booksToMatch = Self.books(5) },
            size: CGSize(width: 393, height: 4000)
        )
        defer { hosted.close() }
        #expect(hosted.stops(labelContaining: findLabel).count == 5)
        #expect(hosted.stops(labelContaining: String(format: String(localized: "hardcover.needs_match_show_all"), 5)
            .titleStyled).isEmpty)
    }

    @Test func showAllRevealsEveryBook() {
        #expect(HardcoverConnectedPhase.booksShown(Self.books(28), showingAll: false).count == 5)
        #expect(HardcoverConnectedPhase.booksShown(Self.books(28), showingAll: true).count == 28)
        #expect(HardcoverConnectedPhase.booksShown(Self.books(3), showingAll: false).count == 3)
    }

    // MARK: m1 — a finished Sync Now is said

    @Test func aFinishedSyncIsAnnouncedOnce() {
        let synced = String(localized: "hardcover.synced_status")
        #expect(HardcoverConnectedPhase.syncAnnouncement(from: .syncing, to: .idle) == synced)
        #expect(HardcoverConnectedPhase.syncAnnouncement(from: .idle, to: .idle) == nil)
        #expect(HardcoverConnectedPhase.syncAnnouncement(from: .idle, to: .syncing) == nil)
        #expect(HardcoverConnectedPhase.syncAnnouncement(from: .syncing, to: .stalled("Stuck.")) == nil)
        #expect(HardcoverConnectedPhase.syncAnnouncement(from: .syncing, to: .syncNowFailed("Didn't finish."))
            == "Didn't finish.")
    }

    // MARK: m2 — decorative glyphs are silent

    /// The audit's element tree listed the statement rows' symbols with their default labels — "Selected" for
    /// `checkmark`, "End", "Bookmark", the raw "clock.arrow.circlepath" — but that tree also lists the children
    /// of combined rows. Read as VoiceOver reads it, no glyph is a stop or lends a row its Selected trait; the
    /// glyphs are hidden all the same, and this keeps it so.
    @Test func decorativeGlyphsAreNotStops() async {
        for history in [HardcoverHistoryModel.offer(books: 75), .available(books: 75), .done(sent: 70, needsMatch: 0)] {
            let hosted = await HostedView(
                connected { $0.history = history },
                size: CGSize(width: 393, height: 4000)
            )
            defer { hosted.close() }
            let symbolNames = ["Selected", "End", "Bookmark", "clock.arrow.circlepath", "Clock", "checkmark.circle",
                               "Checkmark"]
            for stop in hosted.stops {
                let spoken = stop.label.components(separatedBy: ", ")
                for name in symbolNames {
                    #expect(!spoken.contains(name), "\(name) spoken in \(history): \(stop)")
                }
            }
            let anySelected = hosted.stops.contains { $0.isSelected }
            #expect(!anySelected, "\(hosted.tree)")
        }
    }
}
