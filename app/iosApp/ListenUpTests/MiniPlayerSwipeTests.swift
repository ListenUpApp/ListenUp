import SwiftUI
import Testing
@testable import ListenUp

/// A swipe up on the mini player opens the full player: a clear 40pt of travel, or a flick the system projects past
/// 120pt. It never follows the finger; it triggers the same zoom a tap does.
@Suite("Mini player swipe up")
struct MiniPlayerSwipeTests {
    @Test func aClearUpwardSwipeOpens() {
        #expect(MiniPlayerSwipe.opens(translation: -41, predictedEnd: -41))
    }

    @Test func aQuickFlickOpensThoughItBarelyMoved() {
        #expect(MiniPlayerSwipe.opens(translation: -8, predictedEnd: -121))
    }

    @Test func aSmallSlowNudgeDoesNot() {
        #expect(!MiniPlayerSwipe.opens(translation: -12, predictedEnd: -30))
    }

    @Test func downwardNeverOpens() {
        #expect(!MiniPlayerSwipe.opens(translation: 300, predictedEnd: 900))
    }

    @Test func pullingBackBelowFortyBeforeLiftingCancels() {
        #expect(!MiniPlayerSwipe.opens(translation: -20, predictedEnd: -25))
    }

    @Test func theThresholdsAreExclusive() {
        #expect(!MiniPlayerSwipe.opens(translation: MiniPlayerSwipe.travel, predictedEnd: 0))
        #expect(!MiniPlayerSwipe.opens(translation: 0, predictedEnd: MiniPlayerSwipe.projected))
    }

    @Test func onlyTheExpandedCapsuleListens() {
        #expect(MiniPlayerSwipe.isEnabled(placement: .expanded))
        #expect(!MiniPlayerSwipe.isEnabled(placement: .inline))
        #expect(!MiniPlayerSwipe.isEnabled(placement: nil))
    }
}
