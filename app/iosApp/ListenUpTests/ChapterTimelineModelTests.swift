import Testing
import Shared
@testable import ListenUp

/// The iOS timeline over the shared Kotlin lane, driven the way its gestures drive it.
///
/// 2026-09-25: Android committed every pointer move of a drag as its own edit, so Undo walked a
/// drag back a pixel at a time. These pin that iOS commits once, on release, through the same lane.
@MainActor
@Suite("ChapterTimeline")
struct ChapterTimelineModelTests {
    private func chapter(_ id: String, start: Int64) -> Chapter {
        Chapter(id: id, title: id, duration: 0, startTime: start, partTitle: nil, bookTitle: nil)
    }

    /// Two chapters in a 100-second book, the lane 1000 points wide: 100 ms per point.
    private func model(retimes: @escaping (String, Int64) -> Void = { _, _ in }) -> ChapterTimelineModel {
        let model = ChapterTimelineModel()
        model.onRetime = retimes
        model.update(
            chapters: [chapter("c1", start: 0), chapter("c2", start: 50_000)],
            bookDurationMs: 100_000,
            selectedId: nil,
            lockedIds: [],
            ghostStarts: [],
            fileStarts: []
        )
        model.measure(width: 1_000)
        return model
    }

    @Test func aDragCommitsOnceOnReleaseAtWhereItWasPreviewed() {
        var retimes: [(String, Int64)] = []
        let timeline = model { retimes.append(($0, $1)) }

        timeline.press(atX: 500)
        for _ in 0..<10 { timeline.move(dx: 3, pulled: 0) }
        #expect(timeline.readout != nil)
        #expect(retimes.isEmpty)
        timeline.release()

        #expect(retimes.count == 1)
        #expect(retimes.first?.0 == "c2")
        #expect(retimes.first?.1 == 53_000)
        #expect(timeline.readout == nil)
    }

    @Test func pullingAwayMakesTheDragFine() {
        var landed: Int64?
        let timeline = model { landed = $1 }

        timeline.press(atX: 500)
        timeline.move(dx: 10, pulled: 300)
        timeline.release()

        #expect(landed == 50_040)
    }

    @Test func draggingOpenLaneMovesTheWindowAndEditsNothing() {
        var retimes = 0
        let timeline = model { _, _ in retimes += 1 }
        timeline.zoom(by: ChapterTimelineModel.zoomInStep)
        let before = timeline.windowStartMs

        timeline.press(atX: 250)
        timeline.move(dx: -100, pulled: 0)
        timeline.release()

        #expect(timeline.windowStartMs != before)
        #expect(retimes == 0)
    }

    @Test func theZoomButtonsNarrowAndWidenTheWindow() {
        let timeline = model()
        let full = timeline.windowEndMs - timeline.windowStartMs

        timeline.zoom(by: ChapterTimelineModel.zoomInStep)
        #expect(timeline.windowEndMs - timeline.windowStartMs < full)
        timeline.zoom(by: ChapterTimelineModel.zoomOutStep)
        #expect(timeline.windowEndMs - timeline.windowStartMs == full)
    }

    @Test func markersSitWhereTheirTimeFallsAcrossTheWindow() {
        let timeline = model()

        #expect(timeline.markers.map(\.fraction) == [0, 0.5])
        #expect(timeline.markers.map(\.number) == [1, 2])
    }
}
