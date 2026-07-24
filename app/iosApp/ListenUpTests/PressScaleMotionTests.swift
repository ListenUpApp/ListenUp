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
}
