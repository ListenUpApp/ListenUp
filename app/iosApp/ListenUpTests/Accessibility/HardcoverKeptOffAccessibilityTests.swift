import SwiftUI
import Testing
@testable import ListenUp

/// Kept Off Hardcover at the largest text sizes (#1562, M4 and m4).
@MainActor
@Suite("Kept off Hardcover accessibility", .serialized, .flakyOnCI)
struct HardcoverKeptOffAccessibilityTests {
    private let book = KeptOffBookRow(
        id: "b1", title: "Living from a Place of Surrender: The Untethered Soul in Action",
        authorNames: "Michael A. Singer", coverPath: nil, coverHash: nil
    )

    private func row(_ size: DynamicTypeSize) async -> HostedView {
        await HostedView(
            Form { KeptOffBookRowView(book: book) {} },
            size: CGSize(width: 393, height: 2400),
            dynamicTypeSize: size
        )
    }

    private var syncAgainLabel: String {
        String(format: String(localized: "hardcover.sync_again_label"), book.title)
    }

    /// At AX5 the cover, the title and a bordered button side by side crushed the title to a word a line
    /// and broke "Sync Again" mid-word. HIG, Typography: at accessibility sizes, stack what sat side by side.
    @Test func atAccessibilitySizesSyncAgainSitsBelowTheBook() async throws {
        let hosted = await row(.accessibility5)
        defer { hosted.close() }
        let text = try #require(hosted.stops(labelContaining: "Living from a Place").first { !$0.isButton },
                                "\(hosted.tree)")
        let button = try #require(hosted.stop(labelled: syncAgainLabel), "\(hosted.tree)")
        #expect(button.frame.minY >= text.frame.maxY - 1, "button \(button.frame) text \(text.frame)")
        // The title has the row's width, not a sliver of it.
        #expect(text.frame.width >= 280, "text \(text.frame)")
    }

    @Test func atDefaultSizesSyncAgainSitsBesideTheBook() async throws {
        let hosted = await row(.large)
        defer { hosted.close() }
        let text = try #require(hosted.stops(labelContaining: "Living from a Place").first { !$0.isButton },
                                "\(hosted.tree)")
        let button = try #require(hosted.stop(labelled: syncAgainLabel), "\(hosted.tree)")
        #expect(button.frame.minX >= text.frame.maxX - 1, "button \(button.frame) text \(text.frame)")
    }

    /// The large title truncated to "Kept off Har…" at AX5; the inline title has the bar's width.
    @Test func theTitleIsInlineAtAccessibilitySizes() {
        #expect(HardcoverKeptOffView.usesInlineTitle(at: .accessibility1))
        #expect(HardcoverKeptOffView.usesInlineTitle(at: .accessibility5))
        #expect(!HardcoverKeptOffView.usesInlineTitle(at: .xxxLarge))
        #expect(!HardcoverKeptOffView.usesInlineTitle(at: .large))
    }
}
