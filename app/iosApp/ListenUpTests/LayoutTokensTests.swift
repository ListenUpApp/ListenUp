import CoreGraphics
import Testing
@testable import ListenUp

private let radii: [CGFloat] = [Radius.xs, Radius.s, Radius.m, Radius.l, Radius.xl, Radius.xxl]
private let spacings: [CGFloat] = [Spacing.xxs, Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl]

@Suite("Layout tokens")
struct LayoutTokensTests {
    @Test(arguments: [("radius", radii), ("spacing", spacings)])
    func scaleRisesStrictlyWithEveryStep(name: String, scale: [CGFloat]) {
        #expect(zip(scale, scale.dropFirst()).allSatisfy { $0 < $1 }, "\(name) scale is out of order")
    }

    @Test(arguments: [("radius", radii), ("spacing", spacings)])
    func scaleSitsOnTheFourPointGrid(name: String, scale: [CGFloat]) {
        #expect(scale.allSatisfy { $0.truncatingRemainder(dividingBy: 4) == 0 }, "\(name) leaves the 4pt grid")
    }

    /// 16pt is the system's standard content margin; the scale's `m` must stay it.
    @Test func mediumSpacingIsTheStandardMargin() {
        #expect(Spacing.m == 16)
    }
}
