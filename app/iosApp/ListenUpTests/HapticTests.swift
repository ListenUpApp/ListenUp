import SwiftUI
import Testing
@testable import ListenUp

@Suite struct HapticTests {
    @Test func verbMapping() {
        #expect(Haptic.selectionTick.feedback == .selection)
        // A transport press: the restrained end of Apple's palette, not a notification buzz.
        #expect(Haptic.press.feedback == .impact(weight: .light))
        #expect(Haptic.toggleOn.feedback == .impact(weight: .light))
        #expect(Haptic.toggleOff.feedback == .impact(weight: .light))
        #expect(Haptic.longPress.feedback == .impact(weight: .light))
        #expect(Haptic.thresholdActivate.feedback == .impact(flexibility: .rigid))
        // Apple's notification family is what "a task completed" means on iOS; Android uses
        // Confirm. The divergence is the point — do not "fix" it into parity.
        #expect(Haptic.commit.feedback == .success)
    }

    @Test func gateReturnsNilWhenDisabled() {
        #expect(Haptic.selectionTick.feedback(enabled: false) == nil)
        #expect(Haptic.selectionTick.feedback(enabled: true) == .selection)
    }

    @Test func theGateSilencesEveryVerb() {
        let verbs: [Haptic] = [
            .selectionTick, .press, .toggleOn, .toggleOff, .longPress, .thresholdActivate, .commit
        ]
        for haptic in verbs {
            #expect(haptic.feedback(enabled: false) == nil)
        }
    }
}
