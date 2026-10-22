import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// Renders Now Playing per state, light and dark, to PNGs for the design review and for Simon. Off unless
/// LU_SNAPSHOT_DIR is set (xcodebuild passes TEST_RUNNER_LU_SNAPSHOT_DIR through), so it never runs in CI.
/// Test target only: a detached observer, no app launch, no audio.
@MainActor
@Suite("Now Playing review snapshots", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"] != nil))
struct NowPlayingSnapshotExportTests {
    enum Case: String, CaseIterable {
        case playing, paused, busy, sleepArmed, failed, offline, signedOut, held, finished, finishedNoNext, casting,
             wholeBook, largeText
    }

    enum Appearance: String, CaseIterable { case light, dark }

    private static let title = "Bridge Four"

    /// The Way of Kings' shape: 75 chapters of 20:35, so the scrubber, the times and the book sentence agree.
    nonisolated static let chapters = 75
    nonisolated static let chapterMs: Int64 = 1_235_000

    private func state(_ shot: Case) -> PlayerUiStateLoaded {
        switch shot {
        case .playing, .largeText:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title)
        case .paused:
            return .fixtureAt(phase: PlayerPhasePaused.shared, currentIndex: 5, primary: PrimaryControlPlay.shared,
                            chapterTitle: Self.title)
        case .busy:
            return .fixtureAt(phase: PlayerPhaseBuffering.shared, currentIndex: 5, primary: PrimaryControlBusy.shared,
                            chapterTitle: Self.title, showsSkipBack: false)
        case .sleepArmed:
            let timer = SleepTimerStateActive(
                mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer,
                            chapterTitle: Self.title)
        case .failed:
            return .fixtureAt(
                phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: "a1b2c3", debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .offline:
            let offline = PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: offline),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title,
                source: AudioSourceOffline.shared, showsSkipBack: false)
        case .signedOut:
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlSignIn.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .held:
            return .fixtureAt(
                phase: PlayerPhaseUnavailable(reason: BookErrorHeldForReview(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlOpenInbox.shared, chapterTitle: Self.title,
                source: AudioSourceNotPlayable.shared, showsSkipBack: false)
        case .finished:
            return PlayerCardModelTests.finishedState(next: true)
        case .finishedNoNext:
            return PlayerCardModelTests.finishedState(next: false)
        case .casting:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title,
                            source: AudioSourceCasting(deviceName: "Living Room"))
        case .wholeBook:
            return .fixtureAt(phase: PlayerPhasePlaying.shared, wholeBook: true)
        }
    }

    @Test(arguments: Case.allCases, Appearance.allCases)
    func export(_ shot: Case, _ appearance: Appearance) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let size = isPad ? CGSize(width: 1180, height: 820) : CGSize(width: 393, height: 852)
        let progress = Self.progress(in: state(shot))
        let hosted = await HostedView(
            FullScreenPlayerView(observer: .detached(state(shot), progress: progress)), size: size,
            dynamicTypeSize: shot == .largeText ? .accessibility2 : .large,
            appearance: appearance == .dark ? .dark : .light)
        defer { hosted.close() }
        let image = UIGraphicsImageRenderer(bounds: hosted.window.bounds).image { _ in
            hosted.window.drawHierarchy(in: hosted.window.bounds, afterScreenUpdates: true)
        }
        let name = "\(isPad ? "ipad" : "iphone")-\(shot.rawValue)-\(appearance.rawValue).png"
        try #require(image.pngData()).write(to: dir.appendingPathComponent(name))
    }
}

extension NowPlayingSnapshotExportTests {
    /// 12:04 into the current chapter, which puts the book at a floored percent with hours left: a coherent frame.
    static func progress(in state: PlayerUiStateLoaded) -> PlayerProgressRow {
        let chapter = state.chapter
        let into: Int64 = chapter.isWholeBook ? 6_899_000 : 724_000
        return PlayerProgressRow(PlayerProgress(
            chapterPositionMs: into, chapterDurationMs: chapter.durationMs,
            bookPositionMs: chapter.startMs + into, bookDurationMs: state.book.durationMs))
    }
}

private extension PlayerUiStateLoaded {
    /// `fixture` in The Way of Kings' shape (75 chapters of 20:35).
    static func fixtureAt(
        phase: any PlayerPhase = PlayerPhasePaused.shared,
        currentIndex: Int = 0,
        wholeBook: Bool = false,
        primary: any PrimaryControl = PrimaryControlPause.shared,
        sleepTimer: any SleepTimerState = SleepTimerStateInactive.shared,
        chapterTitle: String? = nil,
        source: any AudioSource = AudioSourceStreaming.shared,
        showsSkipBack: Bool = true
    ) -> PlayerUiStateLoaded {
        .fixture(
            phase: phase, currentIndex: currentIndex, wholeBook: wholeBook, primary: primary, sleepTimer: sleepTimer,
            chapterTitle: chapterTitle, source: source, showsSkipBack: showsSkipBack,
            chapterCount: NowPlayingSnapshotExportTests.chapters,
            chapterDurationMs: NowPlayingSnapshotExportTests.chapterMs)
    }
}
