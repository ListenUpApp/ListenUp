import SwiftUI
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// The reader's strip follows the mini player: the chapter line, back N and the main button, and a problem's way out.
@MainActor
@Suite("Reader strip accessibility", .serialized)
struct NowPlayingStripAccessibilityTests {
    private func model(
        _ phase: any PlayerPhase, _ primary: any PrimaryControl, back: Bool
    ) -> MiniPlayerAccessoryModel {
        MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
                phase: phase, currentIndex: 5, primary: primary, chapterTitle: "Bridge Four", showsSkipBack: back)),
            chapterRemainingMs: 511_000, chapterFraction: 0.59, isInline: false, isWide: false)
    }

    private func host(_ model: MiniPlayerAccessoryModel) async -> HostedView {
        await HostedView(
            NowPlayingStripContent(model: model, onSkipBack: {}, onPrimary: {}), size: CGSize(width: 393, height: 120))
    }

    @Test func playingShowsTheChapterLineBackNAndPause() async throws {
        let hosted = await host(model(PlayerPhasePlaying.shared, PrimaryControlPause.shared, back: true))
        defer { hosted.close() }
        #expect(!hosted.stops(labelContaining: "Bridge Four · 8 mins left").isEmpty, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(format: String(localized: "player.skip_backward"), "10")) != nil)
        #expect(hosted.stop(labelled: String(localized: "player.pause")) != nil)
    }

    @Test func aProblemShowsItsLineAndItsWayOut() async throws {
        let failed = model(
            PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            PrimaryControlRetry.shared, back: false)
        let hosted = await host(failed)
        defer { hosted.close() }
        #expect(!hosted.stops(labelContaining: "Couldn't play this book.").isEmpty, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(localized: "player.retry")) != nil, "\(hosted.tree)")
    }

    @Test func theSleepFadeOffersKeepListening() async throws {
        let hosted = await host(model(PlayerPhaseSleepFading.shared, PrimaryControlPause.shared, back: true))
        defer { hosted.close() }
        #expect(hosted.stop(labelled: "Keep Listening") != nil, "\(hosted.tree)")
    }
}
