import CoreGraphics
import Testing
@testable import ListenUp

@Suite("Layout tokens")
struct LayoutTokensTests {
    private static let scales: [(String, [CGFloat])] = [
        ("radius", [Radius.xs, Radius.s, Radius.m, Radius.l, Radius.xl, Radius.xxl]),
        ("spacing", [Spacing.xxs, Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl, Spacing.xxl])
    ]

    @Test func scaleRisesStrictlyWithEveryStep() {
        for (name, scale) in Self.scales {
            #expect(zip(scale, scale.dropFirst()).allSatisfy { $0 < $1 }, "\(name) scale is out of order")
        }
    }

    @Test func scaleSitsOnTheFourPointGrid() {
        for (name, scale) in Self.scales {
            #expect(scale.allSatisfy { $0.truncatingRemainder(dividingBy: 4) == 0 }, "\(name) leaves the 4pt grid")
        }
    }

    /// 16pt is the system's standard content margin; the scale's `m` must stay it.
    @Test func mediumSpacingIsTheStandardMargin() {
        #expect(Spacing.m == 16)
    }
}
