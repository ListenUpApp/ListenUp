import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// Now Playing read the way VoiceOver meets it (player spec §8): the header, the transport by state, the settings row,
/// and the state card that takes the transport's place.
@MainActor
@Suite("Now Playing accessibility", .serialized)
struct NowPlayingAccessibilityTests {
    private func host(_ state: PlayerUiStateLoaded) async -> HostedView {
        await HostedView(FullScreenPlayerView(observer: .detached(state)), size: CGSize(width: 393, height: 852))
    }

    private func index(of label: String, in hosted: HostedView) -> Int? {
        hosted.stops.firstIndex { $0.label == label }
    }

    private static let playing = PlayerUiStateLoaded.fixture(
        phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four")

    @Test func theHeaderReadsCollapseTheStatusThenMore() async throws {
        let hosted = await host(Self.playing)
        defer { hosted.close() }
        let first = hosted.stops.prefix(3).map(\.label)
        #expect(first == [
            String(localized: "player.collapse"),
            String(localized: "player.np_status_streaming"),
            String(localized: "player.more_options")
        ], "\(hosted.tree)")
    }

    @Test func collapseAndMoreHaveAFullTapTarget() async throws {
        let hosted = await host(Self.playing)
        defer { hosted.close() }
        for label in [String(localized: "player.collapse"), String(localized: "player.more_options")] {
            let stop = try #require(hosted.stop(labelled: label), "\(hosted.tree)")
            #expect(stop.frame.width >= 44 && stop.frame.height >= 44, "\(label): \(stop.frame)")
        }
    }

    @Test func onAnIPadAtAccessibilitySizesTheSettingsRowIsThere() async throws {
        let hosted = await HostedView(
            FullScreenPlayerView(observer: .detached(Self.playing)), size: CGSize(width: 1180, height: 820),
            dynamicTypeSize: .accessibility2)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: String(localized: "player.speed")) != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(localized: "player.sleep_timer")) != nil)
    }

    @Test func previousChapterCarriesItsHintAndWorksInChapterOne() async throws {
        let hosted = await host(.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 0))
        defer { hosted.close() }
        let previousLabel = String(localized: "player.previous_chapter")
        let previous = try #require(hosted.stop(labelled: previousLabel), "\(hosted.tree)")
        #expect(previous.hint == String(localized: "player.np_previous_chapter_hint"))
        #expect(!previous.traits.contains(.notEnabled))
    }

    @Test func whileBufferingPreviousWorksAndTheSkipsKeepTheirPlacesDisabled() async throws {
        let hosted = await host(.fixture(
            phase: PlayerPhaseBuffering.shared, currentIndex: 3, primary: PrimaryControlBusy.shared,
            showsSkipBack: false))
        defer { hosted.close() }
        let previousLabel = String(localized: "player.previous_chapter")
        let previous = try #require(hosted.stop(labelled: previousLabel), "\(hosted.tree)")
        #expect(!previous.traits.contains(.notEnabled))
        let back = try #require(hosted.stop(labelled: String(format: String(localized: "player.skip_backward"), "10")))
        #expect(back.traits.contains(.notEnabled))
        #expect(hosted.stop(labelled: String(localized: "player.mini_busy_a11y")) != nil, "\(hosted.tree)")
    }

    @Test func nextChapterIsOffAtTheLastChapter() async throws {
        let hosted = await host(.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 7))
        defer { hosted.close() }
        let next = try #require(hosted.stop(labelled: String(localized: "player.next_chapter")), "\(hosted.tree)")
        #expect(next.traits.contains(.notEnabled))
    }

    @Test func thePlayControlNamesWhatATapDoes() async throws {
        let playing = await host(Self.playing)
        #expect(playing.stop(labelled: String(localized: "player.pause")) != nil, "\(playing.tree)")
        playing.close()
        let paused = await host(.fixture(phase: PlayerPhasePaused.shared, primary: PrimaryControlPlay.shared))
        defer { paused.close() }
        #expect(paused.stop(labelled: String(localized: "player.play")) != nil, "\(paused.tree)")
    }

    @Test func boostLivesInMoreNotInTheSettingsRow() async throws {
        let hosted = await host(Self.playing)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: String(localized: "player.volume_boost")) == nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(localized: "player.speed")) != nil)
        #expect(hosted.stop(labelled: String(localized: "player.sleep_timer")) != nil)
    }

    @Test func anArmedTimerCountsDown() async throws {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
        let hosted = await host(.fixture(phase: PlayerPhasePlaying.shared, sleepTimer: timer))
        defer { hosted.close() }
        let sleep = try #require(hosted.stop(labelled: String(localized: "player.sleep_timer")), "\(hosted.tree)")
        #expect(sleep.value == "23:41")
    }

    @Test func aDroppedConnectionShowsItsCardInTheTransportsPlace() async throws {
        let hosted = await host(.fixture(
            phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        defer { hosted.close() }
        #expect(!hosted.stops(labelContaining: String(localized: "player.title_playback_connection_lost")).isEmpty,
                "\(hosted.tree)")
        #expect(!hosted.stops(labelContaining: String(localized: "player.cause_playback_connection_lost")).isEmpty)
        #expect(!hosted.stops(labelContaining: String(localized: "player.place_saved")).isEmpty)
        let retry = try #require(index(of: String(localized: "player.retry"), in: hosted), "\(hosted.tree)")
        let dismiss = try #require(index(of: String(localized: "player.np_dismiss"), in: hosted), "\(hosted.tree)")
        #expect(retry < dismiss)
        #expect(hosted.stop(labelled: String(localized: "player.pause")) == nil)
        #expect(hosted.stop(labelled: String(localized: "player.play")) == nil)
    }

    @Test func aHeldBookOffersAMemberCloseBookAlone() async throws {
        let hosted = await host(.fixture(
            phase: PlayerPhaseUnavailable(reason: BookErrorHeldForReview(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlClose.shared, showsSkipBack: false))
        defer { hosted.close() }
        #expect(hosted.stop(labelled: String(localized: "player.np_close_book_ios")) != nil, "\(hosted.tree)")
        #expect(hosted.stop(labelled: String(localized: "player.retry")) == nil)
        #expect(hosted.stop(labelled: String(localized: "player.np_dismiss")) == nil)
    }

    @Test func aFinishedBookAsksHowItWasThenOffersTheNextBook() async throws {
        let hosted = await host(PlayerCardModelTests.finishedState(next: true))
        defer { hosted.close() }
        let stars = try #require(hosted.stop(labelled: String(localized: "rating.stars_label")), "\(hosted.tree)")
        #expect(stars.traits.contains(.adjustable))
        let next = try #require(index(of: String(localized: "home.play_next_book_ios"), in: hosted), "\(hosted.tree)")
        let again = try #require(index(of: String(localized: "player.mini_listen_again_ios"), in: hosted))
        #expect(next < again)
    }

    @Test func airPlayNamesTheDeviceItIsPlayingOn() async throws {
        let casting = await host(.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, source: AudioSourceCasting(deviceName: "Living Room")))
        let playingOn = String(format: String(localized: "player.np_status_playing_on"), "Living Room")
        #expect(casting.stops.contains { $0.value == playingOn }, "\(casting.tree)")
        #expect(casting.stop(labelled: "Living Room") != nil, "\(casting.tree)")
        casting.close()

        let local = await host(Self.playing)
        defer { local.close() }
        #expect(local.stop(labelled: String(localized: "player.airplay")) != nil, "\(local.tree)")
        #expect(!local.stops.contains { $0.value == playingOn })
    }

    @Test func eachToneHasItsGlyph() {
        #expect(PlayerStateCardView.glyph(for: .error) == "exclamationmark.triangle.fill")
        #expect(PlayerStateCardView.glyph(for: .held) == "tray.full.fill")
        #expect(PlayerStateCardView.glyph(for: .neutral) == "info.circle.fill")
    }
}
