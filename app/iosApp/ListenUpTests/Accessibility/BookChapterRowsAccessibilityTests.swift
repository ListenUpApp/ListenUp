import SwiftUI
import Testing
@testable import ListenUp

/// Chapter rows are buttons that say what they do — "Play from chapter 7: Chapter 7" — the current one selected, and
/// collapsed to the five-row window around it (spec §2.5).
@MainActor
@Suite("Book Detail chapter rows", .serialized)
struct BookChapterRowsAccessibilityTests {
    private let all = (0..<12).map { index in
        BookChapterRow(id: "c\(index)", title: "Chapter \(index + 1)", duration: "10:00", isCurrent: index == 5,
                       startMs: Int64(index) * 600_000)
    }

    private func host(canPlay: Bool = true, onPlay: @escaping (Int64) -> Void = { _ in }) async -> HostedView {
        await HostedView(NavigationStack {
            ScrollView {
                BookChapterRowsSection(chapters: all, preview: Array(all[3..<8]), canPlay: canPlay,
                                       onPlayFromChapter: onPlay)
                    .padding()
            }
        })
    }

    @Test func rowsAreButtonsNamedPlayFromChapter() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let row = try #require(hosted.stop(labelled: "Play from chapter 7: Chapter 7"), "\(hosted.tree)")
        #expect(row.isButton, "\(row)")
    }

    @Test func theCurrentRowIsSelected() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let row = try #require(hosted.stop(labelled: "Play from chapter 6: Chapter 6"), "\(hosted.tree)")
        #expect(row.isSelected, "\(row)")
        let other = try #require(hosted.stop(labelled: "Play from chapter 7: Chapter 7"), "\(hosted.tree)")
        #expect(!other.isSelected, "\(other)")
    }

    @Test func theCollapsedListIsTheWindow() async {
        let hosted = await host()
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Play from chapter 3: Chapter 3") == nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 8: Chapter 8") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "All 12 Chapters") != nil, "\(hosted.tree)")
    }

    @Test func theChaptersTitleIsAHeading() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let heading = try #require(hosted.stop(labelled: String(localized: "book.detail_chapters")), "\(hosted.tree)")
        #expect(heading.isHeader, "\(heading)")
    }
}
