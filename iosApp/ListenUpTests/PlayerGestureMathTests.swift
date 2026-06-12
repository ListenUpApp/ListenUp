import CoreGraphics
import Testing
@testable import ListenUp

@Suite("PlayerGestureMath")
struct PlayerGestureMathTests {
    // MARK: - Dismiss decision

    @Test func dismissesWhenDraggedPastDistanceThreshold() {
        #expect(PlayerGestureMath.shouldDismiss(translation: 121, predictedEndTranslation: 0))
    }

    @Test func dismissesOnFastDownwardFlingDespiteShortTravel() {
        // Finger barely moved, but the predicted fling carries past the velocity gate.
        #expect(PlayerGestureMath.shouldDismiss(translation: 20, predictedEndTranslation: 301))
    }

    @Test func doesNotDismissOnSmallDragWithoutFling() {
        #expect(!PlayerGestureMath.shouldDismiss(translation: 60, predictedEndTranslation: 120))
    }

    @Test func upwardDragNeverDismisses() {
        #expect(!PlayerGestureMath.shouldDismiss(translation: -200, predictedEndTranslation: -400))
    }

    // MARK: - Expand decision

    @Test func expandsOnClearUpwardSwipe() {
        #expect(PlayerGestureMath.shouldExpand(translation: -41, predictedEndTranslation: 0))
    }

    @Test func expandsOnFastUpwardFlingDespiteShortTravel() {
        #expect(PlayerGestureMath.shouldExpand(translation: -15, predictedEndTranslation: -121))
    }

    @Test func doesNotExpandOnSmallUpwardNudge() {
        #expect(!PlayerGestureMath.shouldExpand(translation: -20, predictedEndTranslation: -60))
    }

    @Test func downwardDragNeverExpands() {
        #expect(!PlayerGestureMath.shouldExpand(translation: 200, predictedEndTranslation: 400))
    }

    // MARK: - Offset clamping

    @Test func downwardOffsetClampsUpwardTravelToZero() {
        #expect(PlayerGestureMath.downwardOffset(translation: -150) == 0)
    }

    @Test func downwardOffsetPassesThroughDownwardTravel() {
        #expect(PlayerGestureMath.downwardOffset(translation: 150) == 150)
    }

    // MARK: - Dismiss progress

    @Test func dismissProgressIsZeroAtRest() {
        #expect(PlayerGestureMath.dismissProgress(offset: 0) == 0)
    }

    @Test func dismissProgressReachesOneAtFadeDistance() {
        #expect(PlayerGestureMath.dismissProgress(offset: PlayerGestureMath.dismissFadeDistance) == 1)
    }

    @Test func dismissProgressClampsBeyondFadeDistance() {
        #expect(PlayerGestureMath.dismissProgress(offset: 10_000) == 1)
    }

    @Test func dismissProgressIsHalfAtMidTravel() {
        let mid = PlayerGestureMath.dismissFadeDistance / 2
        #expect(PlayerGestureMath.dismissProgress(offset: mid) == 0.5)
    }
}
