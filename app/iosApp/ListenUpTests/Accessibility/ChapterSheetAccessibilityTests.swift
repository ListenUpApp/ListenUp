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

    private func host() async -> HostedView {
        await HostedView(
            ChapterListSheet(observer: .detached(Self.state, progress: Self.progress), onDismiss: {}),
            size: CGSize(width: 393, height: 852))
    }

    private var left: String { MiniPlayerAccessoryModel.shortDuration(ms: 40_000) }
    private var currentLabel: String {
        String(format: String(localized: "player.sheet_chapter_current"), 39, "Chapter 39", left)
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
}
