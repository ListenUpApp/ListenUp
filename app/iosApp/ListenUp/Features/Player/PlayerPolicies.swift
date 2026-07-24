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
}
