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

    private func host(canPlay: Bool = true, expanded: Bool = false,
                      onPlay: @escaping (Int64) -> Void = { _ in }) async -> HostedView {
        await HostedView(NavigationStack {
            ScrollView {
                BookChapterRowsSection(chapters: all, preview: Array(all[3..<8]), canPlay: canPlay,
                                       initiallyExpanded: expanded, onPlayFromChapter: onPlay)
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

    @Test func expandedShowsEveryRowAndOffersFewerChapters() async {
        let hosted = await host(expanded: true)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Play from chapter 1: Chapter 1") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 12: Chapter 12") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Fewer Chapters") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "All 12 Chapters") == nil, "\(hosted.tree)")
    }

    // MARK: - A marked book

    private func hostGrouped(canPlay: Bool = true, expanded: Bool = true, currentFolded: Bool = false,
                             onPlay: @escaping (Int64) -> Void = { _ in }) async -> HostedView {
        let open = BookPartHeader(title: "Part I", chapterId: "c0", chapterCount: 2, durationMs: 3_600_000, startMs: 0,
                                  containsCurrent: true, isCurrentChapter: false)
        let shut = BookPartHeader(title: "Part II", chapterId: "c3", chapterCount: 2, durationMs: 3_600_000,
                                  startMs: 1_800_000, containsCurrent: currentFolded, isCurrentChapter: currentFolded)
        let rows: [BookOutlineRow] = [
            .part(open), .chapter(number: 2, partId: "c0"), .chapter(number: 3, partId: "c0"),
            .part(shut), .chapter(number: 5, partId: "c3"), .chapter(number: 6, partId: "c3")
        ]
        let grouped = BookGroupedChapters(rows: rows, previewLabel: "Part I", previewRows: Array(rows[1...3]))
        return await HostedView(NavigationStack {
            ScrollView {
                BookChapterRowsSection(chapters: all, preview: Array(all[0..<5]), grouped: grouped, canPlay: canPlay,
                                       initiallyExpanded: expanded, onPlayFromChapter: onPlay)
                    .padding()
            }
        })
    }

    /// Named by what it is, and saying which way it stands: "Expanded" is what makes it a disclosure.
    @Test func aPartSaysWhetherItIsOpen() async throws {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        let open = try #require(hosted.stops(labelContaining: "Part I,").first, "\(hosted.tree)")
        #expect(open.isButton, "\(open)")
        #expect(open.value == String(localized: "common.expanded"), "\(open)")
        let shut = try #require(hosted.stops(labelContaining: "Part II,").first, "\(hosted.tree)")
        #expect(shut.value == String(localized: "common.collapsed"), "\(shut)")
    }

    /// ⛔ A stop of its own, not an action hidden on the row: it has to be reachable by itself.
    @Test func aPartsPlayIsItsOwnControl() async throws {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        let play = try #require(hosted.stop(labelled: "Play Part II"), "\(hosted.tree)")
        #expect(play.isButton, "\(play)")
    }

    @Test func aClosedPartHidesItsChapters() async {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Play from chapter 2: Chapter 2") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 5: Chapter 5") == nil, "\(hosted.tree)")
    }

    @Test func theFoldedCurrentChapterIsSelectedOnItsHeader() async throws {
        let hosted = await hostGrouped(currentFolded: true)
        defer { hosted.close() }
        let play = try #require(hosted.stop(labelled: "Play Part II"), "\(hosted.tree)")
        #expect(play.isSelected, "\(play)")
    }

    @Test func theWindowNamesItsPartAndItsHeadersClaimNothingToOpen() async throws {
        let hosted = await hostGrouped(expanded: false)
        defer { hosted.close() }
        let label = try #require(hosted.stop(labelled: "Part I"), "\(hosted.tree)")
        #expect(label.isHeader, "\(label)")
        #expect(hosted.stop(labelled: "Play Part II") != nil, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "Part II,").allSatisfy { $0.value.isEmpty }, "\(hosted.tree)")
    }

    @Test func theChaptersTitleIsAHeading() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let heading = try #require(hosted.stop(labelled: String(localized: "book.detail_chapters")), "\(hosted.tree)")
        #expect(heading.isHeader, "\(heading)")
    }
}
