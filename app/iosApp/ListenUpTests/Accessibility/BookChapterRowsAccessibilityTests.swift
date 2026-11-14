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

    /// Opening Credits on its own, then Part I (chapters 2–5) and Part II (6–12); the window is chapters 4–8.
    private let partOne = BookPartHeader(title: "Part I", chapterId: "c1", chapterCount: 4, durationMs: 2_400_000)
    private let partTwo = BookPartHeader(title: "Part II", chapterId: "c5", chapterCount: 7, durationMs: 4_200_000)

    private func hostGrouped(expanded: Bool = true) async -> HostedView {
        let rows: [BookOutlineRow] = [.chapter(index: 0, number: 1), .part(partOne)]
            + (1...4).map { .chapter(index: $0, number: $0 + 1) }
            + [.part(partTwo)]
            + (5...11).map { .chapter(index: $0, number: $0 + 1) }
        let preview: [BookOutlineRow] = [.part(partOne), .chapter(index: 3, number: 4), .chapter(index: 4, number: 5),
                                         .part(partTwo), .chapter(index: 5, number: 6), .chapter(index: 6, number: 7),
                                         .chapter(index: 7, number: 8)]
        let grouped = BookGroupedChapters(rows: rows, previewRows: preview)
        return await HostedView(NavigationStack {
            ScrollView {
                BookChapterRowsSection(chapters: all, preview: Array(all[3..<8]), grouped: grouped, canPlay: true,
                                       initiallyExpanded: expanded, onPlayFromChapter: { _ in })
                    .padding()
            }
        })
    }

    /// ⛔ The rotor walks parts by their headings, so a heading is what it is; and it is no button, so it claims
    /// nothing to open or play.
    @Test func aPartIsAHeadingThatSaysItsCountAndLength() async throws {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        let heading = try #require(hosted.stop(labelled: partTwo.spokenName), "\(hosted.tree)")
        #expect(heading.isHeader, "\(heading)")
        #expect(!heading.isButton, "\(heading)")
        #expect(!heading.isSelected, "\(heading)")
    }

    /// ⛔ Nothing folded: the chapter carrying a part's marker is an ordinary row, and every chapter is listed.
    @Test func everyChapterIsARow() async {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        for number in 1...12 {
            #expect(hosted.stop(labelled: "Play from chapter \(number): Chapter \(number)") != nil, "\(hosted.tree)")
        }
        #expect(hosted.stops(labelContaining: "Play Part").isEmpty, "\(hosted.tree)")
    }

    /// The current highlight sits on the row only, never on its part.
    @Test func onlyTheCurrentRowIsSelected() async {
        let hosted = await hostGrouped()
        defer { hosted.close() }
        #expect(hosted.stops.filter(\.isSelected).map(\.label) == ["Play from chapter 6: Chapter 6"], "\(hosted.tree)")
    }

    /// The window is headed by the part it starts in, and shows the next part's heading where it crosses into it.
    @Test func theWindowShowsAHeadingWhereverItCrossesIntoAPart() async {
        let hosted = await hostGrouped(expanded: false)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: partOne.spokenName)?.isHeader == true, "\(hosted.tree)")
        #expect(hosted.stop(labelled: partTwo.spokenName)?.isHeader == true, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 3: Chapter 3") == nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 8: Chapter 8") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "All 12 Chapters") != nil, "\(hosted.tree)")
    }

    /// ⛔ An omnibus numbers each book from 1: the second book's first chapter says 1, and is still its own chapter.
    @Test func anOmnibusNumbersEachBookFromOne() async throws {
        let rows: [BookOutlineRow] = [.book(title: "Book One", chapterId: "c0")]
            + (0...5).map { .chapter(index: $0, number: $0 + 1) }
            + [.book(title: "Book Two", chapterId: "c6")]
            + (6...11).map { .chapter(index: $0, number: $0 - 5) }
        let played = PlayedStarts()
        let hosted = await HostedView(NavigationStack {
            ScrollView {
                BookChapterRowsSection(chapters: all, preview: Array(all[3..<8]),
                                       grouped: BookGroupedChapters(rows: rows, previewRows: rows), canPlay: true,
                                       initiallyExpanded: true, onPlayFromChapter: { played.starts.append($0) })
                    .padding()
            }
        })
        defer { hosted.close() }
        let book = try #require(hosted.stop(labelled: "Book Two"), "\(hosted.tree)")
        #expect(book.isHeader, "\(book)")
        #expect(hosted.stop(labelled: "Play from chapter 1: Chapter 7") != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: "Play from chapter 1: Chapter 1") != nil, "\(hosted.tree)")
        // Its effect is the proof: SwiftUI reports a styled row button's activation as not taken even when it fires.
        hosted.activate(labelled: "Play from chapter 1: Chapter 7")
        #expect(played.starts == [all[6].startMs])
    }

    @Test func theChaptersTitleIsAHeading() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let heading = try #require(hosted.stop(labelled: String(localized: "book.detail_chapters")), "\(hosted.tree)")
        #expect(heading.isHeader, "\(heading)")
    }
}

/// Where the list asked playback to start.
private final class PlayedStarts {
    var starts: [Int64] = []
}
