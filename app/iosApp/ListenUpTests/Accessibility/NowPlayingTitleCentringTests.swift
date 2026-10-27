import SwiftUI
import Testing
import UIKit
@testable import ListenUp
@preconcurrency import Shared

/// The iPhone title block (Simon, 2026-10-27): the bold title, the chapter's own title, and the mic with "Narrated
/// by …", each centred on the cover to within a point, at a short title, a two-line title and an accessibility size.
/// The HIG accepts either alignment for a media title (Playing audio); Simon chose centred. iPad's wide player keeps its
/// own leading block.
@MainActor
@Suite("Now Playing title centring", .serialized)
struct NowPlayingTitleCentringTests {
    private static func state(title: String = "The Way of Kings") -> PlayerUiStateLoaded {
        .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four", title: title)
    }

    private func phone(_ state: PlayerUiStateLoaded, size: DynamicTypeSize = .large) async -> HostedView {
        await HostedView(
            FullScreenPlayerView(observer: .detached(state)), size: CGSize(width: 393, height: 852),
            dynamicTypeSize: size)
    }

    /// Within a point of the axis: a 2× screen rounds a centred odd width to the pixel, so 1pt is the tolerance.
    private func assertCentred(_ hosted: HostedView, title: String) throws {
        let axis = hosted.usableBounds.midX
        let titleStop = try #require(hosted.stop(labelled: title), "\(hosted.tree)")
        let chapter = try #require(hosted.stop(labelled: "Bridge Four"), "\(hosted.tree)")
        let narrated = try #require(hosted.stop(labelled: String(localized: "book.detail_narrated_by")), "\(hosted.tree)")
        let narrator = try #require(hosted.stop(labelled: "Michael Kramer"), "\(hosted.tree)")
        #expect(abs(titleStop.frame.midX - axis) <= 1, "title \(titleStop.frame) on \(axis)")
        #expect(abs(chapter.frame.midX - axis) <= 1, "chapter \(chapter.frame) on \(axis)")
        // A name's stop includes its 44pt hit area (`minimumTapTarget`, an outset on every side); the line is what is
        // drawn, so the name is measured inside that outset.
        let outset = TapTarget.outset(forVisualSize: 20)
        let line = narrated.frame.union(narrator.frame.insetBy(dx: outset, dy: outset))
        #expect(abs(line.midX - axis) <= 1, "narrator line \(line) on \(axis)")
        #expect(titleStop.frame.maxY <= chapter.frame.minY + 1)
        #expect(chapter.frame.maxY <= line.minY + 1)
    }

    @Test func aShortTitleTheChapterAndTheNarratorsSitOnTheCoversAxis() async throws {
        let hosted = await phone(Self.state())
        defer { hosted.close() }
        try assertCentred(hosted, title: "The Way of Kings")
        #expect(hosted.stop(labelled: "Brandon Sanderson") == nil, "\(hosted.tree)")
        #expect(hosted.stops(labelContaining: "of 8").isEmpty, "\(hosted.tree)")
    }

    @Test func aTwoLineTitleStaysCentred() async throws {
        let long = "The Way of Kings, Being the First Book of the Stormlight Archive"
        let hosted = await phone(Self.state(title: long))
        defer { hosted.close() }
        try assertCentred(hosted, title: long)
    }

    @Test func atAnAccessibilitySizeTheBlockStaysCentred() async throws {
        let hosted = await phone(Self.state(), size: .accessibility3)
        defer { hosted.close() }
        try assertCentred(hosted, title: "The Way of Kings")
    }

    @Test func iPadsWidePlayerKeepsItsLeadingBlockWithTheSeries() async throws {
        let hosted = await HostedView(
            FullScreenPlayerView(observer: .detached(Self.state())), size: CGSize(width: 1180, height: 820))
        defer { hosted.close() }
        let series = try #require(hosted.stop(labelled: "The Stormlight Archive · Book 1"), "\(hosted.tree)")
        let title = try #require(hosted.stop(labelled: "The Way of Kings"), "\(hosted.tree)")
        #expect(abs(title.frame.minX - series.frame.minX) < 1, "title \(title.frame), series \(series.frame)")
        #expect(hosted.stop(labelled: "Brandon Sanderson") != nil, "\(hosted.tree)")
    }
}
