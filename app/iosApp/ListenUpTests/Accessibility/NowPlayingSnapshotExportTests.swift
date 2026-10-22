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

    private func state(_ shot: Case) -> PlayerUiStateLoaded {
        switch shot {
        case .playing, .largeText:
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title)
        case .paused:
            return .fixture(phase: PlayerPhasePaused.shared, currentIndex: 5, primary: PrimaryControlPlay.shared,
                            chapterTitle: Self.title)
        case .busy:
            return .fixture(phase: PlayerPhaseBuffering.shared, currentIndex: 5, primary: PrimaryControlBusy.shared,
                            chapterTitle: Self.title, showsSkipBack: false)
        case .sleepArmed:
            let timer = SleepTimerStateActive(
                mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer,
                            chapterTitle: Self.title)
        case .failed:
            return .fixture(
                phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: "a1b2c3", debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .offline:
            return .fixture(
                phase: PlayerPhaseUnavailable(reason: PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlRetry.shared, chapterTitle: Self.title,
                source: AudioSourceOffline.shared, showsSkipBack: false)
        case .signedOut:
            return .fixture(
                phase: PlayerPhaseUnavailable(reason: AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlSignIn.shared, chapterTitle: Self.title, showsSkipBack: false)
        case .held:
            return .fixture(
                phase: PlayerPhaseUnavailable(reason: BookErrorHeldForReview(correlationId: nil, debugInfo: nil)),
                currentIndex: 5, primary: PrimaryControlOpenInbox.shared, chapterTitle: Self.title,
                source: AudioSourceNotPlayable.shared, showsSkipBack: false)
        case .finished:
            return PlayerCardModelTests.finishedState(next: true)
        case .finishedNoNext:
            return PlayerCardModelTests.finishedState(next: false)
        case .casting:
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: Self.title,
                            source: AudioSourceCasting(deviceName: "Living Room"))
        case .wholeBook:
            return .fixture(phase: PlayerPhasePlaying.shared, wholeBook: true)
        }
    }

    @Test(arguments: Case.allCases, Appearance.allCases)
    func export(_ shot: Case, _ appearance: Appearance) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let size = isPad ? CGSize(width: 1180, height: 820) : CGSize(width: 393, height: 852)
        let progress = PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 724_000, chapterDurationMs: 1_235_000, bookPositionMs: 18_018_000,
            bookDurationMs: 163_800_000))
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
