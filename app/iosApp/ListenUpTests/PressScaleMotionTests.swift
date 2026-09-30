import Testing
@testable import ListenUp

@Suite("PressScaleMotion")
struct PressScaleMotionTests {
    @Test func pressedScalesWhenMotionAllowed() {
        #expect(PressScaleButtonStyle.effectiveScale(pressed: true, base: 0.96, reduceMotion: false) == 0.96)
    }

    @Test func releasedIsFullSize() {
        #expect(PressScaleButtonStyle.effectiveScale(pressed: false, base: 0.96, reduceMotion: false) == 1.0)
    }

    @Test func reduceMotionDisablesScale() {
        #expect(PressScaleButtonStyle.effectiveScale(pressed: true, base: 0.96, reduceMotion: true) == 1.0)
    }

    @Test func reduceMotionStaysFullSizeWhenReleased() {
        #expect(PressScaleButtonStyle.effectiveScale(pressed: false, base: 0.96, reduceMotion: true) == 1.0)
    }

    // MARK: - Disabled

    /// A custom style draws its own label, so it must dim it when disabled — the system styles do
    /// this for themselves, and `.disabled` alone left 13 buttons looking tappable (2026-09-29 audit).
    @Test func aDisabledButtonDimsItsLabel() {
        #expect(PressScaleButtonStyle.labelOpacity(isEnabled: false) < 0.5)
        #expect(PressScaleButtonStyle.labelOpacity(isEnabled: true) == 1)
    }
}
