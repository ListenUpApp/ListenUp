import Foundation
import Observation
import Shared

/// One boundary on the detail lane, as the view draws it: where it sits across the lane's width.
struct LaneMarker: Identifiable, Equatable {
    let id: String
    let number: Int
    /// 0…1 across the visible window; outside that range the marker is off the lane.
    let fraction: Double
    let isSelected: Bool
    let isLocked: Bool
}

/// What the timeline draws from: the editor's state, flattened by the observer.
struct TimelineInput {
    let chapters: [Chapter]
    let bookDurationMs: Int64
    let selectedId: String?
    let lockedIds: Set<String>
    /// The drift preview's corrected starts, drawn as ghosts.
    let ghostStarts: [Int64]
    /// Where each audio file begins, drawn as faint dividers.
    let fileStarts: [Int64]
}

/// The chapter timeline (spec §7.3) over the shared Kotlin `TimelineLane` the other clients drive.
///
/// SwiftUI translates its gestures into these calls — a drag on the lane, a pinch, the minimap, the
/// zoom buttons — and draws the flattened values this publishes. The lane itself (hit-testing,
/// the fine-scrub pull, the preview, where a boundary lands) is Kotlin, shared with Android and the
/// web, so a drag behaves identically on all three.
///
/// **One drag is one edit.** A drag previews while it moves and `release()` commits it once through
/// `onRetime`. Committing every movement made Undo walk a drag back a pixel at a time on Android.
@Observable
@MainActor
final class ChapterTimelineModel {
    private(set) var markers: [LaneMarker] = []
    private(set) var ghostFractions: [Double] = []
    private(set) var fileFractions: [Double] = []
    /// The drag's readout — `25× · 0:00:50.04` — while a boundary is being dragged.
    private(set) var readout: String?
    /// Minimap buckets, each 0…1 relative to the busiest.
    private(set) var density: [Double] = []
    /// The window the lane shows, as fractions of the whole book, for the minimap's viewport box.
    private(set) var viewportStart: Double = 0
    private(set) var viewportEnd: Double = 1
    private(set) var windowStartMs: Int64 = 0
    private(set) var windowEndMs: Int64 = 0

    /// A drag was released: the boundary and where it lands.
    var onRetime: (String, Int64) -> Void = { _, _ in }

    private var lane: TimelineLane?
    private var chapters: [Chapter] = []
    private var bookDurationMs: Int64 = 0
    private var selectedId: String?
    private var lockedIds: Set<String> = []
    private var ghostStarts: [Int64] = []
    private var fileStarts: [Int64] = []

    private static let minimapBuckets: Int32 = 90
    /// One zoom button press: a fifth narrower, or a quarter wider — the other clients' step.
    static let zoomInStep: Float = 0.8
    static let zoomOutStep: Float = 1.25

    /// Takes the editor's current state. The lane opens at the start; `centre(onMs:)` moves it to the
    /// playhead once the view knows where that is.
    func update(_ input: TimelineInput) {
        chapters = input.chapters
        bookDurationMs = input.bookDurationMs
        selectedId = input.selectedId
        lockedIds = input.lockedIds
        ghostStarts = input.ghostStarts
        fileStarts = input.fileStarts
        if lane == nil {
            lane = TimelineLane.Companion.shared.opening(
                bookDurationMs: bookDurationMs,
                aroundMs: nil,
                widthPx: 0
            )
        }
        density = ExportedKotlinPackages.com.calypsan.listenup.client.presentation.chaptereditor.timeline
            .chapterDensity(
                chapterStartsMs: chapters.map(\.startTime),
                bookDurationMs: bookDurationMs,
                bucketCount: Self.minimapBuckets
            )
            .map(Double.init)
        publish()
    }

    // MARK: - Gestures

    /// The lane's real width, so presses map to real time.
    func measure(width: Double) { change { $0.measured(widthPx: Float(width)) } }

    /// A press on the lane at [x]: grabs the boundary under it, unless it is locked.
    func press(atX x: Double) {
        change { $0.grabbed(xPx: Float(x), markers: timelineMarkers()) }
    }

    /// A movement of [dx] sideways, [pulled] points away from where the press began.
    func move(dx: Double, pulled: Double) {
        change { current in
            current.drag == nil
                // Open lane: dragging moves the window rather than a boundary.
                ? current.panned(dxPx: Float(dx), bookDurationMs: bookDurationMs)
                : current.dragged(dxPx: Float(dx), pulledDp: Float(pulled), shiftHeld: false)
        }
    }

    /// The finger lifted: commits the drag once, then ends it.
    func release() {
        guard let lane else { return }
        if let active = lane.drag,
           let landing = lane.committedStartMs(chapters: chapters, bookDurationMs: bookDurationMs) {
            onRetime(active.chapterId, landing)
        }
        change { $0.released() }
    }

    /// A pinch: [scale] above 1 spreads the fingers, which shows less time.
    func pinch(scale: Double, atX x: Double) {
        guard scale > 0 else { return }
        change { $0.zoomed(factor: Float(1 / scale), focusPx: Float(x), bookDurationMs: bookDurationMs) }
    }

    /// The zoom buttons, around the middle of the window.
    func zoom(by factor: Float) {
        change { $0.zoomedAroundCentre(factor: factor, bookDurationMs: bookDurationMs) }
    }

    /// The minimap: centre the lane on [fraction] of the book.
    func centre(onFraction fraction: Double) {
        let ms = Int64(max(0, min(1, fraction)) * Double(bookDurationMs))
        change { $0.centredOn(ms: ms, bookDurationMs: bookDurationMs) }
    }

    /// Moves the lane so [ms] sits in its middle — where the listener is, when the editor opens.
    func centre(onMs ms: Int64) {
        change { $0.centredOn(ms: ms, bookDurationMs: bookDurationMs) }
    }

    /// Where [ms] sits across the visible window, 0…1.
    func fraction(of ms: Int64) -> Double {
        let span = Double(windowEndMs - windowStartMs)
        return span <= 0 ? 0 : Double(ms - windowStartMs) / span
    }

    // MARK: - Internals

    private func change(_ transform: (TimelineLane) -> TimelineLane) {
        guard let lane else { return }
        self.lane = transform(lane)
        publish()
    }

    private func timelineMarkers() -> [TimelineChapter] {
        chapters.enumerated().map { index, chapter in
            TimelineChapter(
                id: chapter.id,
                number: Int32(index + 1),
                startMs: chapter.startTime,
                locked: lockedIds.contains(chapter.id),
                selected: chapter.id == selectedId
            )
        }
    }

    private func publish() {
        guard let lane else { return }
        windowStartMs = lane.geometry.windowStartMs
        windowEndMs = lane.geometry.windowEndMs
        let total = Double(max(bookDurationMs, 1))
        viewportStart = Double(windowStartMs) / total
        viewportEnd = Double(windowEndMs) / total
        let previewed = lane.preview(chapters: chapters, bookDurationMs: bookDurationMs)
        markers = previewed.enumerated().map { index, chapter in
            LaneMarker(
                id: chapter.id,
                number: index + 1,
                fraction: fraction(of: chapter.startTime),
                isSelected: chapter.id == selectedId,
                isLocked: lockedIds.contains(chapter.id)
            )
        }
        ghostFractions = ghostStarts.map(fraction(of:))
        fileFractions = fileStarts.map(fraction(of:))
        readout = lane.readout(chapters: chapters, bookDurationMs: bookDurationMs)
    }
}
