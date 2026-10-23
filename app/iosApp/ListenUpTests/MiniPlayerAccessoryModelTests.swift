import Foundation
import Testing
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// `minutes` in the system's abbreviated units: "8 mins" on iOS 26, "8 min" on iOS 27. The mini line speaks the
/// system's own words, so expectations read them from the system rather than pinning one release's spelling.
func systemMinutes(_ minutes: Int64) -> String {
    Duration.seconds(minutes * 60).formatted(.units(allowed: [.hours, .minutes], width: .abbreviated))
}

@Suite("The mini player accessory, per state")
@MainActor
struct MiniPlayerAccessoryModelTests {
    /// 511 s left in the chapter: eight minutes, floored.
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
        let mini = model(playing())
        #expect(mini.line == "Bridge Four · \(systemMinutes(8)) left")
        #expect(mini.showsSkipBack)
        #expect(!mini.showsSkipForward)
        #expect(mini.primary == .pause)
        #expect(mini.primaryAction == .shared)
        #expect(mini.primaryLabel == String(localized: "player.pause"))
        #expect(mini.showsProgress)
    }

    @Test("iPad: the same line, and forward")
    func iPad() {
        let mini = model(playing(), wide: true)
        #expect(mini.line == "Bridge Four · \(systemMinutes(8)) left")
        #expect(mini.showsSkipForward)
    }

    @Test("an untitled chapter reads by its number; a chapterless book reads Whole book")
    func chapterNames() {
        let untitled = PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "")
        #expect(model(untitled).line == "Chapter 6 · \(systemMinutes(8)) left")
        let generic = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, chapterTitle: "Track 7")
        #expect(model(generic).line == "Track 7 · \(systemMinutes(8)) left")
        let wholeBook = PlayerUiStateLoaded.fixture(phase: PlayerPhasePlaying.shared, wholeBook: true)
        #expect(model(wholeBook).line == "Whole book · \(systemMinutes(8)) left")
    }

    @Test("inline: title and the main button only")
    func inline() {
        let mini = model(playing(), inline: true)
        #expect(mini.line == nil)
        #expect(!mini.showsSkipBack)
        #expect(!mini.showsSkipForward)
        #expect(!mini.showsProgress)
        #expect(mini.primary == .pause)
    }

    @Test("paused says so and offers Play")
    func paused() {
        let mini = model(playing(PlayerPhasePaused.shared, primary: PrimaryControlPlay.shared))
        #expect(mini.line == "Paused · Bridge Four · \(systemMinutes(8)) left")
        #expect(mini.primary == .play)
        #expect(mini.primaryLabel == String(localized: "player.play"))
    }

    @Test("a stall is busy, named Loading. Pause, with no back")
    func busy() {
        let mini = model(playing(PlayerPhaseBuffering.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(mini.line == "Loading…")
        #expect(mini.primary == .busy)
        #expect(mini.primaryLabel == "Loading. Pause")
        #expect(!mini.showsSkipBack)
    }

    @Test("preparing says Starting")
    func preparing() {
        let mini = model(playing(PlayerPhasePreparing.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(mini.line == "Starting…")
    }

    @Test("a running timer replaces the time left")
    func sleepArmed() {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_380_000, totalMs: 1_800_000, startedAt: 0)
        let state = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four")
        #expect(model(state).line == "Bridge Four · Sleep · \(systemMinutes(23))")
    }

    @Test("an end-of-chapter timer counts down the chapter's remainder at the listening speed, never 0")
    func sleepArmedEndOfChapter() {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        let atOne = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four",
            speed: 1.0)
        #expect(model(atOne).line == "Bridge Four · Sleep · \(systemMinutes(8))")
        // 511 s of chapter at 1.5× is 340 s of your evening: five minutes, floored.
        let atOneAndAHalf = PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four")
        #expect(model(atOneAndAHalf).line == "Bridge Four · Sleep · \(systemMinutes(5))")
    }

    @Test("the sleep fade offers Keep Listening, which cancels the timer, and keeps back N")
    func fadingOut() {
        let mini = model(playing(PlayerPhaseSleepFading.shared, primary: PrimaryControlPause.shared))
        #expect(mini.line == "Sleep timer · fading out")
        #expect(mini.primary == .pill("Keep Listening", prominent: false))
        #expect(mini.primaryLabel == "Keep Listening")
        #expect(mini.primaryAction == .keepListening)
        // On a phone the fade gives back N's room to the title and Keep Listening; back stays in Now Playing.
        #expect(!mini.showsSkipBack)
        #expect(model(playing(PlayerPhaseSleepFading.shared, primary: PrimaryControlPause.shared), wide: true)
            .showsSkipBack)
    }

    @Test("the line splits into a lead that may truncate and a tail that never does")
    func lineParts() {
        let playingMini = model(playing())
        #expect(playingMini.lineLead == "Bridge Four")
        #expect(playingMini.lineTail == "\(systemMinutes(8)) left")
        let pausedMini = model(playing(PlayerPhasePaused.shared, primary: PrimaryControlPlay.shared))
        #expect(pausedMini.lineLead == "Paused · Bridge Four")
        #expect(pausedMini.lineTail == "\(systemMinutes(8)) left")
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_380_000, totalMs: 1_800_000, startedAt: 0)
        let armed = model(PlayerUiStateLoaded.fixture(
            phase: PlayerPhasePlaying.shared, currentIndex: 5, sleepTimer: timer, chapterTitle: "Bridge Four"))
        #expect(armed.lineTail == "Sleep · \(systemMinutes(23))")
        let finished = model(
            playing(PlayerPhaseFinished.shared, primary: PrimaryControlListenAgain.shared, showsSkipBack: false))
        #expect(finished.lineLead == "Finished")
        #expect(finished.lineTail == nil)
        #expect(model(playing(), inline: true).lineLead == nil)
    }

    @Test("forward is live only where something can play")
    func forwardFollowsPlayability() {
        #expect(model(playing(), wide: true).canSkipForward)
        let busy = playing(PlayerPhaseBuffering.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false)
        #expect(model(busy, wide: true).canSkipForward)
        let failed = playing(
            PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false)
        #expect(!model(failed, wide: true).canSkipForward)
        let done = playing(PlayerPhaseFinished.shared, primary: PrimaryControlListenAgain.shared, showsSkipBack: false)
        #expect(!model(done, wide: true).canSkipForward)
    }

    @Test("a failure is marked, says why, and offers a Retry capsule")
    func failure() {
        let mini = model(playing(
            PlayerPhaseFailed(error: PlaybackErrorConnectionLost(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        #expect(mini.isFailure)
        #expect(mini.line == "Couldn't play this book.")
        #expect(mini.primary == .pill(String(localized: "player.retry"), prominent: true))
        #expect(mini.primaryAction == .shared)
        #expect(!mini.showsSkipBack)
    }

    @Test("offline without a download is not a failure, and offers Retry")
    func offline() {
        let mini = model(playing(
            PlayerPhaseUnavailable(reason: PlaybackErrorOfflineNotDownloaded(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlRetry.shared, showsSkipBack: false))
        #expect(!mini.isFailure)
        #expect(mini.line == "Can't play offline")
    }

    @Test("a lapsed session offers Sign in")
    func signedOut() {
        let mini = model(playing(
            PlayerPhaseUnavailable(reason: AuthErrorSessionExpired(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlSignIn.shared, showsSkipBack: false))
        #expect(mini.line == "Signed out · place saved")
        #expect(!mini.isFailure)
        #expect(mini.primary == .pill(String(localized: "auth.sign_in"), prominent: true))
    }

    @Test("a revoked book offers Close")
    func revoked() {
        let mini = model(playing(
            PlayerPhaseUnavailable(reason: PlaybackErrorNoLongerAvailable(correlationId: nil, debugInfo: nil)),
            primary: PrimaryControlClose.shared, showsSkipBack: false))
        #expect(mini.line == "No longer in your library")
        #expect(mini.primary == .close)
        #expect(mini.primaryLabel == String(localized: "player.close_book"))
    }

    @Test("finished offers Listen Again for this book")
    func finished() {
        let mini = model(
            playing(PlayerPhaseFinished.shared, primary: PrimaryControlListenAgain.shared, showsSkipBack: false))
        #expect(mini.line == "Finished")
        #expect(mini.primary == .pill("Listen Again", prominent: false))
        #expect(mini.primaryLabel == "Listen again to The Way of Kings")
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
        let mini = model(
            playing(PlayerPhaseReconnecting.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false))
        #expect(mini.line == String(localized: "player.reconnecting"))
        #expect(mini.primary == .busy)
        #expect(mini.primaryLabel == "Reconnecting. Pause")
        #expect(!mini.showsSkipBack)
        #expect(mini.isReconnecting)
    }

    @Test("inline while reconnecting: no line; the word lives in the control's label")
    func reconnectingInline() {
        let state = playing(PlayerPhaseReconnecting.shared, primary: PrimaryControlBusy.shared, showsSkipBack: false)
        let mini = model(state, inline: true)
        #expect(mini.line == nil)
        #expect(mini.primaryLabel == "Reconnecting. Pause")
    }

    @Test("short durations: the system's abbreviated hours and minutes; seconds spelled out")
    func durations() {
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 511_000) == systemMinutes(8))
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 3_900_000) == systemMinutes(65))
        #expect(MiniPlayerAccessoryModel.shortDuration(ms: 42_000) == "42 seconds")
    }
}
