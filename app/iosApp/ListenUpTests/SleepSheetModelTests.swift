import Foundation
import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// The Sleep sheet's three faces (player spec §5.5.2, PT1–PT8), decided from the snapshot, the progress and the clock.
@MainActor
@Suite("The Sleep sheet's model")
struct SleepSheetModelTests {
    /// 11:18:19 PM UTC on 2026-10-23: 23:41 later is 11:42 PM.
    static let now = Date(timeIntervalSince1970: 1_792_797_499)
    static let english = Locale(identifier: "en_US")
    static let utc = TimeZone(identifier: "UTC")!

    /// 12 of a 20-minute chapter played: 8 minutes of chapter left.
    static let progress = PlayerProgressRow(PlayerProgress(
        chapterPositionMs: 720_000, chapterDurationMs: 1_200_000, bookPositionMs: 6_720_000,
        bookDurationMs: 90_000_000))

    static func state(
        phase: any PlayerPhase = PlayerPhasePlaying.shared,
        sleepTimer: any SleepTimerState = SleepTimerStateInactive.shared,
        wholeBook: Bool = false,
        speed: Float = 1.0,
        chapterTitle: String = "Bridge Four",
        numberOffset: Int = 0
    ) -> PlayerUiStateLoaded {
        .fixture(phase: phase, currentIndex: 5, wholeBook: wholeBook, sleepTimer: sleepTimer,
                 chapterTitle: chapterTitle, chapterDurationMs: 1_200_000, speed: speed, numberOffset: numberOffset)
    }

    static func model(_ state: PlayerUiStateLoaded) -> SleepSheetModel {
        SleepSheetModel.make(
            snapshot: PlayerSnapshot.from(state), progress: progress, now: now, locale: english, timeZone: utc)
    }

    /// The system's own spaces (a narrow no-break space before "PM") read as plain ones.
    static func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "\u{202F}", with: " ").replacingOccurrences(of: "\u{00A0}", with: " ")
    }

    @Test("choosing in a chaptered book offers End of Chapter, named and timed in content time")
    func choosing() {
        #expect(Self.model(Self.state()).mode == .choosing(endOfChapter: "After Bridge Four · in \(systemMinutes(8))"))
        #expect(Self.model(Self.state(speed: 2.0)).mode
            == .choosing(endOfChapter: "After Bridge Four · in \(systemMinutes(8))"))
    }

    @Test("an untitled chapter is named by the book's own number, not its position")
    func untitledChapterUsesItsNumber() {
        // The sixth chapter in the list, numbered 8 by the book (front matter before it).
        #expect(Self.model(Self.state(chapterTitle: "", numberOffset: 2)).mode
            == .choosing(endOfChapter: "After Chapter 8 · in \(systemMinutes(8))"))
    }

    @Test("a chapterless book has no End of Chapter")
    func wholeBook() {
        #expect(Self.model(Self.state(wholeBook: true)).mode == .choosing(endOfChapter: nil))
    }

    @Test("a running 30-minute timer: the countdown, the clock it pauses at, the remaining share, and extend")
    func runningDuration() throws {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
        guard case let .running(countdown, pausesAt, meter, canExtend, chapterLine) =
            Self.model(Self.state(sleepTimer: timer)).mode else {
            Issue.record("expected running")
            return
        }
        #expect(countdown == "23:41")
        #expect(Self.plain(pausesAt) == "Pauses at 11:42 PM")
        #expect(abs(meter - 0.789) < 0.001)
        #expect(canExtend)
        #expect(chapterLine == nil)
    }

    @Test("a running end-of-chapter timer counts the chapter down and cannot be extended")
    func runningEndOfChapter() throws {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeEndOfChapter.shared, remainingMs: 0, totalMs: 0, startedAt: 0)
        guard case let .running(countdown, pausesAt, meter, canExtend, chapterLine) =
            Self.model(Self.state(sleepTimer: timer)).mode else {
            Issue.record("expected running")
            return
        }
        #expect(countdown == "8:00")
        #expect(Self.plain(pausesAt) == "Pauses at 11:26 PM")
        #expect(abs(meter - 0.4) < 0.001)
        #expect(!canExtend)
        #expect(chapterLine == "Until the end of Bridge Four · \(systemMinutes(8))")
    }

    @Test("the fade shows its own face")
    func fading() {
        #expect(Self.model(Self.state(phase: PlayerPhaseSleepFading.shared)).mode == .fading)
    }
}

@MainActor
@Suite("The Sleep sheet, read by VoiceOver", .serialized)
struct SleepSheetAccessibilityTests {
    private final class Calls {
        var endOfChapter = 0
        var minutes: [Int] = []
        var started: [Int] = []
        var extended: [Int] = []
        var turnedOff = 0
        var keptListening = 0
    }

    private func host(
        _ state: PlayerUiStateLoaded, calls: Calls = Calls(), size: CGSize = CGSize(width: 393, height: 852),
        dynamicTypeSize: DynamicTypeSize = .large
    ) async -> HostedView {
        let actions = SleepSheetActions(
            endOfChapter: { calls.endOfChapter += 1 }, minutes: { calls.minutes.append($0) },
            start: { calls.started.append($0) }, extend: { calls.extended.append($0) },
            turnOff: { calls.turnedOff += 1 }, keepListening: { calls.keptListening += 1 })
        return await HostedView(
            SleepSheetContent(model: SleepSheetModelTests.model(state), actions: actions),
            size: size, dynamicTypeSize: dynamicTypeSize)
    }

    @Test func choosingListsPlainButtonsWithEndOfChapterFirst() async throws {
        let hosted = await host(SleepSheetModelTests.state())
        defer { hosted.close() }
        let buttons = hosted.stops.filter(\.isButton)
        let first = try #require(buttons.first, "\(hosted.tree)")
        #expect(first.label.hasPrefix(String(localized: "player.sheet_end_of_chapter_ios")), "\(hosted.tree)")
        for minutes in [15, 30, 45, 60, 120] {
            let label = SleepTimerOption.formatDuration(minutes)
            let stop = try #require(hosted.stop(labelled: label), "\(label) missing:\n\(hosted.tree)")
            #expect(stop.isButton)
        }
    }

    @Test func theCustomTimeIsOneAdjustableStopAndStartSendsItsMinutes() async throws {
        let calls = Calls()
        let hosted = await host(SleepSheetModelTests.state(), calls: calls)
        defer { hosted.close() }
        let minutes = { (value: Int) in String(format: String(localized: "player.sheet_custom_minutes"), value) }
        let custom = try #require(hosted.stops.first { $0.traits.contains(.adjustable) }, "\(hosted.tree)")
        #expect(custom.label == String(localized: "player.sheet_custom"))
        #expect(custom.value == minutes(20))
        #expect(custom.frame.height >= 44, "\(custom.frame)")
        #expect(hosted.increment(adjustableLabelled: custom.label))
        await hosted.settle()
        let raised = try #require(hosted.stops.first { $0.traits.contains(.adjustable) }, "\(hosted.tree)")
        #expect(raised.value == minutes(25))
        #expect(hosted.activate(labelled: String(localized: "player.sheet_start")))
        #expect(calls.started == [25])
    }

    /// End of Chapter's subtitle is read as text on its row, light and dark (HIG, Accessibility: 4.5:1).
    @Test(arguments: [UIUserInterfaceStyle.light, .dark])
    func endOfChapterSubtitleReadsOnItsRow(appearance: UIUserInterfaceStyle) async throws {
        let actions = SleepSheetActions(
            endOfChapter: {}, minutes: { _ in }, start: { _ in }, extend: { _ in }, turnOff: {}, keepListening: {})
        let hosted = await HostedView(
            SleepSheetContent(model: SleepSheetModelTests.model(SleepSheetModelTests.state()), actions: actions),
            appearance: appearance)
        defer { hosted.close() }
        let row = try #require(
            hosted.stops.first { $0.label.hasPrefix(String(localized: "player.sheet_end_of_chapter_ios")) },
            "\(hosted.tree)")
        // Composited, as the snapshot export draws: `layer.render` misses the text in dark.
        // Inset past the row's hairline separator, which would otherwise read as a faint last "line" in dark.
        let lines = DrawnContrast(hosted, composited: true).textLines(in: row.frame.insetBy(dx: 0, dy: 3))
        #expect(lines.count >= 2, "\(lines)")
        if let subtitle = lines.last {
            #expect(subtitle >= ContrastMinimum.text, "subtitle \(subtitle) in \(appearance.rawValue)")
        }
    }

    /// Starting a timer moves you on, so the sheet closes; turning one off keeps it open for another choice (§3.8, S13).
    @Test func startingATimerClosesTheSheetAndTurningItOffDoesNot() async throws {
        let shown = PresentedFlag()
        let actions = SleepSheetActions(
            endOfChapter: {}, minutes: { _ in }, start: { _ in }, extend: { _ in }, turnOff: {}, keepListening: {})
        let choosing = await HostedView(SheetPresenter(flag: shown) {
            SleepSheetContent(model: SleepSheetModelTests.model(SleepSheetModelTests.state()), actions: actions)
        })
        await choosing.settle()
        #expect(choosing.activate(labelled: String(localized: "player.sheet_start")), "\(choosing.tree)")
        await choosing.settle()
        #expect(!shown.isPresented)
        choosing.close()

        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
        let stays = PresentedFlag()
        let running = await HostedView(SheetPresenter(flag: stays) {
            SleepSheetContent(
                model: SleepSheetModelTests.model(SleepSheetModelTests.state(sleepTimer: timer)), actions: actions)
        })
        defer { running.close() }
        await running.settle()
        #expect(running.activate(labelled: String(localized: "player.sheet_turn_off_timer_ios")), "\(running.tree)")
        #expect(running.activate(labelled: String(format: String(localized: "player.extend_minutes"), 5)))
        await running.settle()
        #expect(stays.isPresented)
    }

    @Test func atAccessibilitySizesChoosingScrolls() async {
        let hosted = await host(
            SleepSheetModelTests.state(), size: CGSize(width: 393, height: 430), dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        #expect(hosted.scrollsVertically)
    }

    @Test func theRunningCountdownIsNotALiveRegion() async throws {
        let timer = SleepTimerStateActive(
            mode: SleepTimerModeDuration(minutes: 30), remainingMs: 1_421_000, totalMs: 1_800_000, startedAt: 0)
        let calls = Calls()
        let hosted = await host(SleepSheetModelTests.state(sleepTimer: timer), calls: calls)
        defer { hosted.close() }
        let countdown = try #require(hosted.stop(labelled: "23:41"), "\(hosted.tree)")
        #expect(!countdown.traits.contains(.updatesFrequently))
        #expect(hosted.activate(labelled: String(format: String(localized: "player.extend_minutes"), 10)))
        #expect(calls.extended == [10])
        #expect(hosted.activate(labelled: String(localized: "player.sheet_turn_off_timer_ios")))
        #expect(calls.turnedOff == 1)
    }

    @Test func theFadeOffersKeepListening() async throws {
        let calls = Calls()
        let hosted = await host(SleepSheetModelTests.state(phase: PlayerPhaseSleepFading.shared), calls: calls)
        defer { hosted.close() }
        #expect(hosted.activate(labelled: String(localized: "player.mini_keep_listening_ios")), "\(hosted.tree)")
        #expect(calls.keptListening == 1)
    }
}

/// Whether a test's sheet is still presented: what a `dismiss()` from inside it flips.
@MainActor
@Observable
final class PresentedFlag {
    var isPresented = true
}

/// Presents `content` as a sheet from a blank page, the way Now Playing presents the player's sheets.
struct SheetPresenter<Content: View>: View {
    @Bindable var flag: PresentedFlag
    @ViewBuilder let content: () -> Content

    var body: some View {
        Color.clear.sheet(isPresented: $flag.isPresented) { content() }
    }
}
