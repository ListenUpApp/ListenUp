import SwiftUI
import Testing
@testable import ListenUp

@Suite struct HapticTests {
    @Test func verbMapping() {
        #expect(Haptic.selectionTick.feedback == .selection)
        #expect(Haptic.toggleOn.feedback == .impact(weight: .light))
        #expect(Haptic.toggleOff.feedback == .impact(weight: .light))
        #expect(Haptic.longPress.feedback == .impact(weight: .light))
        #expect(Haptic.thresholdActivate.feedback == .impact(flexibility: .rigid))
        #expect(Haptic.commit.feedback == .impact(weight: .medium))
    }

    @Test func gateReturnsNilWhenDisabled() {
        #expect(Haptic.selectionTick.feedback(enabled: false) == nil)
        #expect(Haptic.selectionTick.feedback(enabled: true) == .selection)
    }
}
