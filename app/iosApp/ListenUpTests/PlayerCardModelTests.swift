import Foundation
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

@Suite("The shared state card as native values")
@MainActor
struct PlayerCardModelTests {
    @Test("no card while playing")
    func playing() {
        #expect(PlayerSnapshot.from(PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared)).card == nil)
    }

    @Test("a dropped connection: its title and cause from the catalog, place saved, Retry and Dismiss, error tone")
    func connectionLost() throws {
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared)
        let card = try #require(PlayerSnapshot.from(state).card)
        guard case let .problem(title, cause, placeSaved, reference, tone) = card.kind else {
            Issue.record("expected a problem card")
            return
        }
        #expect(title == String(localized: "player.title_playback_connection_lost"))
        #expect(cause == String(localized: "player.cause_playback_connection_lost"))
        #expect(placeSaved)
        #expect(reference == nil)
        #expect(tone == .error)
        #expect(card.primary == .retry)
        #expect(card.secondary == .dismiss)
    }

    @Test("a correlation id becomes the reference line")
    func reference() throws {
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseFailed(error: PlaybackErrorStalled(correlationId: "req-42", debugInfo: nil)),
            primary: PrimaryControlRetry.shared)
        let card = try #require(PlayerSnapshot.from(state).card)
        guard case let .problem(_, _, _, reference, _) = card.kind else {
            Issue.record("expected a problem card")
            return
        }
        #expect(reference == String(format: String(localized: "player.reference"), "req-42"))
    }

    @Test("held for an admin: Open Inbox and Close Book, held tone")
    func held() throws {
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseUnavailable(reason: BookErrorHeldForReview(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlOpenInbox.shared)
        let card = try #require(PlayerSnapshot.from(state).card)
        #expect(card.primary == .openInbox)
        #expect(card.secondary == .closeBook)
        guard case let .problem(_, _, _, _, tone) = card.kind else {
            Issue.record("expected a problem card")
            return
        }
        #expect(tone == .held)
    }

    @Test("finished with a next book leads with Play Next Book")
    func finished() throws {
        let card = try #require(PlayerSnapshot.from(Self.finishedState(next: true)).card)
        guard case let .finished(model) = card.kind else {
            Issue.record("expected finished")
            return
        }
        #expect(model.yourHalfStars == 8)
        #expect(model.next?.title == "Words of Radiance")
        #expect(card.primary == .playNextBook)
        #expect(card.secondary == .listenAgain)
    }

    @Test("the sleep fade: the gold card with Keep Listening alone")
    func sleepFading() throws {
        let card = try #require(PlayerSnapshot.from(PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseSleepFading.shared)).card)
        #expect(card.kind == .sleepFading)
        #expect(card.primary == .keepListening)
        #expect(card.secondary == nil)
        #expect(PlayerCardModel.Action(PlayerCardActionKeepListening.shared) == .keepListening)
        #expect(PlayerCardModel.Action.keepListening.title == String(localized: "player.mini_keep_listening_ios"))
    }

    @Test("each native action is the shared one")
    func actionsRoundTrip() {
        for action in PlayerCardModel.Action.allCases {
            #expect(PlayerCardModel.Action(action.kotlin) == action)
        }
    }

    @Test("a revoked book closes Now Playing")
    func revoked() {
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseUnavailable(reason: PlaybackErrorNoLongerAvailable(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlClose.shared)
        #expect(PlayerSnapshot.from(state).closesNowPlaying)
        #expect(!PlayerSnapshot.from(PlayerUiStateLoaded.fixture()).closesNowPlaying)
    }

    static func finishedState(next: Bool) -> PlayerUiStateLoaded {
        let book = NextInSeries(
            bookId: "b2", title: "Words of Radiance", coverPath: nil, coverHash: nil, narratorNames: nil,
            seriesName: "The Stormlight Archive", sequenceLabel: "2", durationMs: 172_800_000)
        let info = FinishedInfo(
            finishedAtMs: 1_791_651_600_000, startedAtMs: 1_788_652_800_000, durationMs: 163_800_000,
            yourHalfStars: 8, nextBook: next ? book : nil)
        return PlayerUiStateLoaded.fixture(
            phase: PlayerPhaseFinished.shared, primary: PrimaryControlListenAgain.shared, showsSkipBack: false,
            finished: info)
    }
}
