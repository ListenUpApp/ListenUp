import Foundation
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

@Suite("The mini player accessory, per state")
@MainActor
struct MiniPlayerAccessoryModelTests {
    /// 511 s left in the chapter: "8 min".
    private let left: Int64 = 511_000

    private func model(
        _ state: PlayerUiStateLoaded,
        inline: Bool = false,
        wide: Bool = false
    ) -> MiniPlayerAccessoryModel {
        MiniPlayerAccessoryModel.make(
            snapshot: PlayerSnapshot.from(state), chapterRemainingMs: left, chapterFraction: 0.59,
            isInline: inline, isWide: wide)
    }

    private func playing(
        _ phase: any PlayerPhase = PlayerPhasePlaying.shared,
        primary: any PrimaryControl = PrimaryControlPause.shared,
        showsSkipBack: Bool = true
    ) -> PlayerUiStateLoaded {
        .fixture(
            phase: phase, currentIndex: 5, primary: primary, chapterTitle: "Bridge Four", showsSkipBack: showsSkipBack)
    }

    @Test("iPhone: the book's own chapter title and time left, back N and Pause")
    func iPhone() {
        let m = model(playing())
        #expect(m.line == "Bridge Four · 8 min left")
        #expect(m.showsSkipBack)
        #expect(!m.showsSkipForward)
        #expect(m.primary == .pause)
        #expect(m.primaryAction == .shared)
        #expect(m.primaryLabel == String(localized: "player.pause"))
        #expect(m.showsProgress)
    }

    @Test("iPad: the same line, and forward")
    func iPad() {
        let m = model(playing(), wide: true)
        #expect(m.line == "Bridge Four · 8 min left")
        #expect(m.showsSkipForward)
    }

    @Test("an untitled chapter reads by its number; a chapterless book reads Whole book")
    func chapterNames() {
        let untitled = PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "")
        #expect(model(untitled).line == "Chapter 6 · 8 min left")
        let generic = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Track 7")
        #expect(model(generic).line == "Track 7 · 8 min left")
        let wholeBook = PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, wholeBook: true)
        #expect(model(wholeBook).line == "Whole book · 8 min left")
    }

    @Test("inline: title and the main button only")
    func inline() {
        let m = model(playing(), inline: true)
        #expect(m.line == nil)
        #expect(!m.showsSkipBack)
        #expect(!m.showsSkipForward)
        #expect(!m.showsProgress)
        #expect(m.primary == .pause)
    }

    @Test("paused says so and offers Play")
    func paused() {
        let m = model(playing(PlayerPhasePaused.shared, primary: PrimaryControlPlay.shared))
        #expect(m.line == "Paused · Bridge Four · 8 min left")
        #expect(m.primary == .play)
        #expect(m.primaryLabel == String(localized: "player.play"))
    }

    @Test("a stall is busy, named Loading. Pause, with no back")
    func busy() {
        let m = model(playing(PlayerPhaseBuffering.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(m.line == "Loading…")
        #expect(m.primary == .busy)
        #expect(m.primaryLabel == "Loading. Pause")
        #expect(!m.showsSkipBack)
    }

    @Test("preparing says Starting")
    func preparing() {
        let m = model(playing(PlayerPhasePreparing.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(m.line == "Starting…")
    }

    @Test("a running timer replaces the time left")
    func sleepArmed() {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_380_000, totalMs: 1_800_000, startedAt: 0)
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four")
        #expect(model(state).line == "Bridge Four · Sleep · 23 min")
    }

    @Test("the sleep fade offers Keep Listening, which cancels the timer, and keeps back N")
    func fadingOut() {
        let m = model(playing(PlayerPhaseSleepFading.shared, primary: PrimaryControlPause.shared))
        #expect(m.line == "Sleep timer · fading out")
        #expect(m.primary == .pill("Keep Listening", prominent: false))
        #expect(m.primaryLabel == "Keep Listening")
        #expect(m.primaryAction == .keepListening)
        #expect(m.showsSkipBack)
    }

    @Test("a failure is marked, says why, and offers a Retry capsule")
    func failure() {
        let m = model(playing(
            PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        #expect(m.isFailure)
        #expect(m.line == "Couldn't play this book.")
        #expect(m.primary == .pill(String(localized: "player.retry"), prominent: true))
        #expect(m.primaryAction == .shared)
        #expect(!m.showsSkipBack)
    }

    @Test("offline without a download is not a failure, and offers Retry")
    func offline() {
        let m = model(playing(
            PlayerPhaseUnavailable(reason: PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        #expect(!m.isFailure)
        #expect(m.line == "Can't play offline")
    }

    @Test("a lapsed session offers Sign in")
    func signedOut() {
        let m = model(playing(
            PlayerPhaseUnavailable(reason: AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlSignIn.shared, showsSkipBack: false))
        #expect(m.line == "Signed out · place saved")
        #expect(!m.isFailure)
        #expect(m.primary == .pill(String(localized: "auth.sign_in"), prominent: true))
    }

    @Test("a revoked book offers Close")
    func revoked() {
        let m = model(playing(
            PlayerPhaseUnavailable(reason: PlaybackErrorNoLongerAvailable(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlClose.shared, showsSkipBack: false))
        #expect(m.line == "No longer in your library")
        #expect(m.primary == .close)
        #expect(m.primaryLabel == String(localized: "player.close_book"))
    }

    @Test("finished offers Listen Again for this book")
    func finished() {
        let m = model(
            playing(PlayerPhaseFinished.shared, primary: PrimaryControlListenAgain.shared, showsSkipBack: false))
        #expect(m.line == "Finished")
        #expect(m.primary == .pill("Listen Again", prominent: false))
        #expect(m.primaryLabel == "Listen again to The Way of Kings")
    }

    @Test("casting names the device and the chapter")
    func casting() {
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Bridge Four",
            source: AudioSourceCasting(deviceName: "Living Room"))
        #expect(model(state).line == "Playing on Living Room · Bridge Four")
    }

    @Test("a dropped connection reads Reconnecting, and the busy control says so")
    func reconnecting() {
        let m = model(
            playing(PlayerPhaseReconnecting.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(m.line == String(localized: "player.reconnecting"))
        #expect(m.primary == .busy)
        #expect(m.primaryLabel == "Reconnecting. Pause")
        #expect(!m.showsSkipBack)
        #expect(m.isReconnecting)
    }

    @Test("inline while reconnecting: no line; the word lives in the control's label")
    func reconnectingInline() {
        let m = model(
            playing(PlayerPhaseReconnecting.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false), inline: true)
        #expect(m.line == nil)
        #expect(m.primaryLabel == "Reconnecting. Pause")
    }

    @Test("short durations: minutes, hours and minutes, seconds")
    func durations() {
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 511_000) == "8 min")
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 3_900_000) == "1 hr, 5 min")
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 42_000) == "42 sec")
    }
}
