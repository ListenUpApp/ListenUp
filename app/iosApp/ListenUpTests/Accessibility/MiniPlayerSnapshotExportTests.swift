import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// Renders the accessory, in the real system tab bar, per state, to PNGs for the design review and for Simon.
/// Off unless LU_SNAPSHOT_DIR is set (xcodebuild passes TEST_RUNNER_LU_SNAPSHOT_DIR through), so it never runs in CI.
/// Test target only: no app launch, no audio.
@MainActor
@Suite("Mini player review snapshots", .serialized,
       .enabled(if: ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"] != nil))
struct MiniPlayerSnapshotExportTests {
    enum Case: String, CaseIterable {
        case playing, paused, busy, reconnecting, sleepArmed, fadingOut, failed, offline, signedOut, revoked, finished,
             casting
    }

    private static let failedError = PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)
    private static let offlineError = PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)
    private static let sessionError = AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)
    private static let revokedError = PlaybackErrorNoLongerAvailable(correlationId: nil, debugInfo: nil)

    private func state(_ shot: Case) -> PlayerUiStateLoaded {
        let title = "Bridge Four"
        switch shot {
        case .playing:
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: title)
        case .paused:
            return .fixture(phase: PlayerPhasePaused.shared, currentIndex: 5, primary: PrimaryControlPlay.shared,
                            chapterTitle: title)
        case .busy:
            return .fixture(phase: PlayerPhaseBuffering.shared, currentIndex: 5, primary: PrimaryControlBusy.shared,
                            chapterTitle: title, showsSkipBack: false)
        case .reconnecting:
            return .fixture(phase: PlayerPhaseReconnecting.shared, currentIndex: 5, primary: PrimaryControlBusy.shared,
                            chapterTitle: title, showsSkipBack: false)
        case .sleepArmed:
            let timer = SleepTimerStateActive(
                mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_380_000, totalMs: 1_800_000, startedAt: 0)
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: title)
        case .fadingOut:
            return .fixture(phase: PlayerPhaseSleepFading.shared, currentIndex: 5, chapterTitle: title)
        case .failed:
            return .fixture(phase: PlayerPhaseFailed(error: Self.failedError), currentIndex: 5,
                            primary: PrimaryControlRetry.shared, showsSkipBack: false)
        case .offline:
            return .fixture(phase: PlayerPhaseUnavailable(reason: Self.offlineError), currentIndex: 5,
                            primary: PrimaryControlRetry.shared, showsSkipBack: false)
        case .signedOut:
            return .fixture(phase: PlayerPhaseUnavailable(reason: Self.sessionError), currentIndex: 5,
                            primary: PrimaryControlSignIn.shared, showsSkipBack: false)
        case .revoked:
            return .fixture(phase: PlayerPhaseUnavailable(reason: Self.revokedError), currentIndex: 5,
                            primary: PrimaryControlClose.shared, showsSkipBack: false)
        case .finished:
            return .fixture(phase: PlayerPhaseFinished.shared, currentIndex: 5,
                            primary: PrimaryControlListenAgain.shared, showsSkipBack: false)
        case .casting:
            return .fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: title,
                            source: AudioSourceCasting(deviceName: "Living Room"))
        }
    }

    @Test(arguments: Case.allCases)
    func export(_ shot: Case) async throws {
        let dir = URL(fileURLWithPath: try #require(ProcessInfo.processInfo.environment["LU_SNAPSHOT_DIR"]))
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        let model = MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(state(shot)), chapterRemainingMs: 511_000, chapterFraction: 0.59,
            isInline: false, isWide: isPad)
        let size = isPad ? CGSize(width: 1180, height: 820) : CGSize(width: 393, height: 852)
        let hosted = await HostedView(TabHarness(model: model), size: size)
        defer { hosted.close() }
        let image = UIGraphicsImageRenderer(bounds: hosted.window.bounds).image { _ in
            hosted.window.drawHierarchy(in: hosted.window.bounds, afterScreenUpdates: true)
        }
        let name = "\(isPad ? "ipad" : "iphone")-\(shot.rawValue).png"
        try #require(image.pngData()).write(to: dir.appendingPathComponent(name))
    }
}

/// A two-tab shell with the accessory attached, so the snapshot shows the system's own Liquid Glass capsule.
private struct TabHarness: View {
    let model: MiniPlayerAccessoryModel
    @Namespace private var namespace

    var body: some View {
        TabView {
            Tab("Home", systemImage: "house") { Color(.systemBackground) }
            Tab("Library", systemImage: "books.vertical") { Color(.systemBackground) }
        }
        .tabViewBottomAccessory {
            MiniPlayerAccessoryContent(
                model: model, isInline: false, transitionNamespace: namespace,
                onOpen: {}, onSkipBack: {}, onSkipForward: {}, onPrimary: {})
        }
    }
}
