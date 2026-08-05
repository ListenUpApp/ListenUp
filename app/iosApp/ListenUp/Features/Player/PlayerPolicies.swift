import SwiftUI
import AVFoundation
@preconcurrency import Shared

// Pure, coordinator-free decision helpers for the player — split out of `PlayerCoordinator` so each
// is unit-testable in isolation (and to keep the coordinator file focused).

/// Pure decision for an audio-session interruption — testable without notifications.
enum InterruptionPolicy {
    enum Action: Equatable { case pause, resume, none }
    static func action(type: AVAudioSession.InterruptionType, shouldResume: Bool) -> Action {
        switch type {
        case .began: return .pause
        case .ended: return shouldResume ? .resume : .none
        @unknown default: return .none
        }
    }
}

/// Pure decision for an audio-route change — testable without notifications.
enum RouteChangePolicy {
    /// Pause only when the previous output device went away (headphones/AirPods
    /// unplugged) — otherwise audio would blast out of the speaker (charter rule 13).
    static func shouldPause(reason: AVAudioSession.RouteChangeReason) -> Bool {
        reason == .oldDeviceUnavailable
    }
}

/// Pure decision: should backgrounding to `newPhase` trigger a position save?
/// Only the genuine `.background` transition should — `.inactive` fires constantly
/// (Control Center, banners, app switcher) and would redundantly re-save (charter rule 13).
enum ScenePhasePolicy {
    static func shouldSavePosition(on newPhase: ScenePhase) -> Bool {
        newPhase == .background
    }
}

/// Pure predicate for the load-generation guard — a book-switch epoch check, testable
/// without a coordinator. Each `play(bookId:)` bumps the coordinator's generation; an
/// in-flight prepare captures the generation it started under and bails after every
/// `await` if a newer switch has superseded it, so a slow prepare for book A can never
/// stomp the state of a book B the user has since switched to (RC-4).
enum LoadGeneration {
    static func isSuperseded(taskGeneration: Int, current: Int) -> Bool {
        taskGeneration != current
    }
}

/// Pure chapter math — resolves a whole-book position to a chapter index.
/// Split out so it is testable without a coordinator.
enum ChapterMath {
    /// The index of the chapter containing `positionMs`, or `nil` for an empty
    /// list. A position past the last chapter clamps to the last index.
    static func index(forPositionMs positionMs: Int64, in chapters: [Chapter]) -> Int? {
        guard !chapters.isEmpty else { return nil }
        for (index, chapter) in chapters.enumerated()
        where positionMs < chapter.startTime + chapter.duration {
            return index
        }
        return chapters.count - 1
    }

    /// A chapter-scoped view of the book, as the lock screen and Control Center present it:
    /// duration is the current chapter's length and elapsed is the offset within it, the same way
    /// a music player shows the current track rather than the whole album.
    ///
    /// Deliberately mirrors the Android `ChapterWindow` type — same name, same fields, same
    /// chapterless fallback — so the two platforms cannot drift on what "the current chapter"
    /// means. `index` is `-1` for a chapterless book, which presents as one whole-book window.
    struct Window: Equatable, Sendable {
        let index: Int
        let startMs: Int64
        let durationMs: Int64
        let count: Int
    }

    /// The [Window] containing `positionMs`.
    ///
    /// A window ends where the NEXT chapter starts (or at `bookDurationMs` for the last one),
    /// **not** at `start + duration`. Chapters carry no stored end, and a chapter's own `duration`
    /// can disagree with the gap to the next chapter; trusting it would leave positions that fall
    /// in no window at all. Contiguity is the property the lock screen needs.
    /// Resolution is deliberately NOT [index(forPositionMs:in:)]: that walks `start + duration`, so a
    /// position falling in a gap — where a chapter's stored duration undershoots the distance to the
    /// next chapter — is attributed to the FOLLOWING chapter, and the lock screen would jump a
    /// chapter early. This picks the last chapter starting at or before the position, which is
    /// gap-proof and matches Android's `currentChapterWindow`.
    ///
    /// The two therefore disagree inside a gap: `PlayerCoordinator.chapterIndex` (in-app UI) still
    /// uses the duration walk. Unifying them is a real follow-up, deliberately not folded in here —
    /// it would change in-app chapter display, which this change is scoped to leave alone.
    static func window(forPositionMs positionMs: Int64, in chapters: [Chapter], bookDurationMs: Int64) -> Window {
        guard !chapters.isEmpty else {
            return Window(index: -1, startMs: 0, durationMs: max(0, bookDurationMs), count: 0)
        }
        let index = chapters.lastIndex { $0.startTime <= positionMs } ?? 0
        let start = chapters[index].startTime
        let end = index + 1 < chapters.count ? chapters[index + 1].startTime : bookDurationMs
        return Window(index: index, startMs: start, durationMs: max(0, end - start), count: chapters.count)
    }

    /// Translate a window-relative position (what the lock-screen scrubber reports once the info
    /// center is chapter-scoped) back into a book-relative position, clamped to the window.
    ///
    /// This is the ONLY place that translation may happen. Everything downstream speaks book
    /// coordinates. Android shipped a production bug (#1251) by letting a book-relative value reach
    /// a chapter-relative surface; a single, tested translation point is what prevents the mirror
    /// image of it here.
    static func bookPosition(forWindowPositionMs windowPositionMs: Int64, in window: Window) -> Int64 {
        window.startMs + min(max(0, windowPositionMs), window.durationMs)
    }
}
