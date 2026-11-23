import SwiftUI
import Testing
@testable import ListenUp

@Suite("ProgressFillShape")
struct ProgressFillShapeTests {
    @Test("zero, negative, and NaN progress with no minWidth yield an empty path")
    func emptyWhenZeroOrInvalid() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 10)
        for value in [0, -0.25, .nan, -.infinity] {
            let path = ProgressFillShape(progress: value).path(in: rect)
            #expect(path.isEmpty, "expected empty path for progress \(value)")
        }
    }

    @Test("zero-sized rect always yields an empty path even with minWidth")
    func emptyWhenRectHasNoArea() {
        let zeroWidth = ProgressFillShape(progress: 0.5, minWidth: 4).path(in: CGRect(x: 0, y: 0, width: 0, height: 10))
        let zeroHeight = ProgressFillShape(progress: 0.5, minWidth: 4).path(in: CGRect(x: 0, y: 0, width: 100, height: 0))
        #expect(zeroWidth.isEmpty)
        #expect(zeroHeight.isEmpty)
    }

    @Test("minWidth sets a floor clamped by container width")
    func minWidthFloorAndClamp() {
        let normal = ProgressFillShape(progress: 0, minWidth: 4).path(in: CGRect(x: 0, y: 0, width: 100, height: 10))
        #expect(normal.boundingRect == CGRect(x: 0, y: 0, width: 4, height: 10))

        let narrowerContainer = ProgressFillShape(progress: 0, minWidth: 4)
            .path(in: CGRect(x: 0, y: 0, width: 2, height: 10))
        #expect(narrowerContainer.boundingRect == CGRect(x: 0, y: 0, width: 2, height: 10))
    }

    @Test("mid-range progress scales rect width and preserves non-zero origin")
    func midRangeAndOrigin() {
        let originRect = CGRect(x: 10, y: 5, width: 100, height: 10)
        let path = ProgressFillShape(progress: 0.5).path(in: originRect)
        #expect(path.boundingRect == CGRect(x: 10, y: 5, width: 50, height: 10))
    }

    @Test("overflow progress clamps to container width")
    func overflowClamps() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 10)
        for value in [1.0, 1.5, .infinity] {
            let path = ProgressFillShape(progress: value).path(in: rect)
            #expect(path.boundingRect == rect)
        }
    }

    @Test("corner radius modes produce valid bounds even when narrow")
    func cornerRadiusModes() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 10)

        let sharp = ProgressFillShape(progress: 0.5, cornerRadius: 0).path(in: rect)
        #expect(sharp.boundingRect == CGRect(x: 0, y: 0, width: 50, height: 10))

        let explicit = ProgressFillShape(progress: 0.5, cornerRadius: Radius.s).path(in: rect)
        #expect(explicit.boundingRect == CGRect(x: 0, y: 0, width: 50, height: 10))

        let narrowCapsule = ProgressFillShape(progress: 0.02).path(in: rect)
        #expect(narrowCapsule.boundingRect == CGRect(x: 0, y: 0, width: 2, height: 10))
    }

    @Test("mirrors in RTL and round-trips animatableData")
    func rtlAndAnimatableData() {
        var shape = ProgressFillShape(progress: 0.25)
        #expect(shape.layoutDirectionBehavior == .mirrors)
        #expect(shape.animatableData == 0.25)
        shape.animatableData = 0.75
        #expect(shape.progress == 0.75)
    }
}
