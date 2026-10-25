import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// The Chapters sheet (player spec §5.5.4, PC1–PC4): it opens centred on the chapter you're in, names that chapter as
/// playing with its time left, and offers every other one as a place to play from.
@MainActor
@Suite("The Chapters sheet", .serialized)
struct ChapterSheetAccessibilityTests {
    /// 68 one-minute chapters, in chapter 39 with 40 seconds of it left.
    private static let state = PlayerUiStateLoaded.fixture(
        phase: PlayerPhasePlaying.shared, currentIndex: 38, chapterCount: 68, chapterDurationMs: 60_000)
    private static let progress = PlayerProgressRow(PlayerProgress(
        chapterPositionMs: 20_000, chapterDurationMs: 60_000, bookPositionMs: 38 * 60_000 + 20_000,
        bookDurationMs: 68 * 60_000))

    private func host(appearance: UIUserInterfaceStyle = .light) async -> HostedView {
        await HostedView(
            ChapterListSheet(observer: .detached(Self.state, progress: Self.progress), onDismiss: {}),
            size: CGSize(width: 393, height: 852), appearance: appearance)
    }

    private var left: String { MiniPlayerAccessoryModel.shortDuration(ms: 40_000) }
    private var currentLabel: String {
        String(
            format: String(localized: "player.sheet_chapter_current"), 39, "Chapter 39",
            MiniPlayerAccessoryModel.spokenDuration(ms: 40_000))
    }

    @Test func itOpensCentredOnTheCurrentChapter() async throws {
        let hosted = await host()
        defer { hosted.close() }
        await hosted.settle()
        let current = try #require(hosted.stop(labelled: currentLabel), "\(hosted.tree)")
        let scroll = try #require(hosted.scrollViews.max { $0.bounds.height < $1.bounds.height })
        let viewport = scroll.convert(scroll.bounds, to: nil)
        #expect(abs(current.frame.midY - viewport.midY) <= current.frame.height,
                "row \(current.frame) viewport \(viewport)")
    }

    @Test func theCurrentRowIsSelectedAndSaysItIsPlaying() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let current = try #require(hosted.stop(labelled: currentLabel), "\(hosted.tree)")
        #expect(current.isSelected)
        let visible = String(format: String(localized: "player.sheet_playing_left_ios"), left)
        #expect(!hosted.stops(labelContaining: visible).isEmpty || current.label.contains(left), "\(hosted.tree)")
    }

    @Test func everyOtherRowOffersToPlayFromIt() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let next = String(format: String(localized: "player.sheet_chapter_play_from"), 40, "Chapter 40", "1:00")
        let row = try #require(hosted.stop(labelled: next), "\(hosted.tree)")
        #expect(row.isButton)
        #expect(row.frame.height >= 44)
    }

    @Test func theHeaderSumsUpTheBook() async throws {
        let hosted = await host()
        defer { hosted.close() }
        let summary = String(
            format: String(localized: "player.chapters_count_summary"), 68, DurationFormatting.abbreviated(ms: 4_080_000))
        #expect(hosted.stop(labelled: summary) != nil, "\(hosted.tree)")
    }

    @Test func theCurrentRowShowsNowPlayingAndItsTimeLeft() {
        #expect(ChapterRow.currentLine(remainingMs: 40_000)
            == String(format: String(localized: "player.sheet_playing_left_ios"), left))
    }

    /// Every row's number, duration and play glyph, and the current row's "Now Playing · …" line, read as text on the row
    /// they sit on, light and dark (HIG, Accessibility: 4.5:1 for text; the glyph shares the duration's ink).
    @Test(arguments: [UIUserInterfaceStyle.light, .dark])
    func everyRowsQuietTextReadsOnItsRow(appearance: UIUserInterfaceStyle) async throws {
        let hosted = await host(appearance: appearance)
        defer { hosted.close() }
        await hosted.settle()
        let next = String(format: String(localized: "player.sheet_chapter_play_from"), 40, "Chapter 40", "1:00")
        let other = try #require(hosted.stop(labelled: next), "\(hosted.tree)")
        let current = try #require(hosted.stop(labelled: currentLabel), "\(hosted.tree)")
        // Composited, as the snapshot export draws: `layer.render` misses the text in dark.
        let drawn = DrawnContrast(hosted, composited: true)
        let style = appearance.rawValue
        // Inset past each row's hairline separator, which would otherwise read as a faint extra "line" in dark.
        let rows = [("other", other.frame), ("current", current.frame)].map { ($0.0, $0.1.insetBy(dx: 0, dy: 3)) }
        for (name, row) in rows {
            let number = CGRect(x: row.minX, y: row.minY, width: 46, height: row.height)
            let trailing = CGRect(x: row.maxX - 120, y: row.minY, width: 120, height: row.height)
            for (part, rect) in [("number", number), ("duration and glyph", trailing)] {
                let lines = drawn.textLines(in: rect)
                #expect(!lines.isEmpty, "\(name) \(part) drew nothing")
                for line in lines {
                    #expect(line >= ContrastMinimum.text, "\(name) \(part) \(line) in \(style)")
                }
            }
        }
        let middle = CGRect(
            x: current.frame.minX + 50, y: current.frame.minY + 3, width: current.frame.width - 180,
            height: current.frame.height - 6)
        let lines = drawn.textLines(in: middle)
        #expect(lines.count >= 2, "\(lines)")
        if let playingLine = lines.last {
            #expect(playingLine >= ContrastMinimum.text, "now playing line \(playingLine) in \(style)")
        }
    }
}
