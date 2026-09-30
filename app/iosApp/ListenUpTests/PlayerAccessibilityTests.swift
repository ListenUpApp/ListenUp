import Foundation
import SwiftUI
import Testing
@testable import ListenUp

/// The chapter scrubber speaks a time, not raw milliseconds, and VoiceOver's swipe up/down seeks
/// straight away by the listener's own skip interval (HIG, VoiceOver: "Provide alternative labels
/// for all key interface elements").
@Suite("Chapter scrubber accessibility")
struct ScrubberAccessibilityTests {
    private func spoken(_ seconds: Int) -> String {
        Duration.seconds(seconds).formatted(.units(allowed: [.hours, .minutes, .seconds], width: .wide))
    }

    @Test func valueSpeaksElapsedOfDuration() {
        let value = ScrubberAccessibility.value(elapsedMs: 754_000, durationMs: 2_700_000)
        #expect(value == "\(spoken(754)) of \(spoken(2_700))")
    }

    @Test func valueNeverSpeaksANegativeElapsed() {
        let value = ScrubberAccessibility.value(elapsedMs: -5_000, durationMs: 60_000)
        #expect(value == "\(spoken(0)) of \(spoken(60))")
    }

    @Test func swipeUpSkipsForward() {
        #expect(ScrubberAccessibility.skip(for: .increment) == .forward)
    }

    @Test func swipeDownSkipsBackward() {
        #expect(ScrubberAccessibility.skip(for: .decrement) == .backward)
    }
}

/// Speed and sleep are system menus with pickers (as in Podcasts); these are the entries and
/// which one carries the checkmark.
@Suite("Sleep timer menu")
struct SleepTimerOptionTests {
    @Test func offersOffTheDurationsAndEndOfChapter() {
        #expect(SleepTimerOption.menuOptions == [
            .off, .minutes(15), .minutes(30), .minutes(45), .minutes(60), .minutes(120), .endOfChapter
        ])
    }

    @Test func noTimerChecksOff() {
        #expect(SleepTimerOption.selection(isActive: false, isEndOfChapter: false) == .off)
    }

    @Test func anEndOfChapterTimerChecksEndOfChapter() {
        #expect(SleepTimerOption.selection(isActive: true, isEndOfChapter: true) == .endOfChapter)
    }

    /// A running countdown no longer equals the duration it was armed with, so no entry is
    /// checked — the menu's header shows the time left instead of a stale "30 min".
    @Test func aRunningCountdownChecksNothing() {
        #expect(SleepTimerOption.selection(isActive: true, isEndOfChapter: false) == nil)
    }

    /// The same 5/10/15 ladder Android's sheet and web's picker offer, so a listener finds the
    /// same choices on every device.
    @Test func extendsByFiveTenOrFifteenMinutes() {
        #expect(SleepTimerOption.extensionMinutes == [5, 10, 15])
    }

    /// Extending is arithmetic on a countdown: an end-of-chapter timer has none to add to, and
    /// the shared manager ignores the request there — so the menu doesn't offer it.
    @Test func onlyARunningCountdownCanBeExtended() {
        #expect(SleepTimerOption.canExtend(isActive: true, isEndOfChapter: false))
        #expect(!SleepTimerOption.canExtend(isActive: true, isEndOfChapter: true))
        #expect(!SleepTimerOption.canExtend(isActive: false, isEndOfChapter: false))
    }
}
