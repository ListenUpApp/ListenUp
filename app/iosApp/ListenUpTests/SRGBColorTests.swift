import Testing
@testable import ListenUp

@Suite("SRGBColor — WCAG contrast arithmetic")
struct SRGBColorTests {
    private let white = SRGBColor(red: 1, green: 1, blue: 1)
    private let black = SRGBColor(red: 0, green: 0, blue: 0)

    private func hex(_ value: UInt32) -> SRGBColor {
        SRGBColor(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    @Test("black on white is the 21:1 ceiling and a colour on itself is 1:1")
    func extremes() {
        #expect(abs(black.contrastRatio(against: white) - 21) < 0.001)
        #expect(abs(white.contrastRatio(against: black) - 21) < 0.001)
        #expect(abs(hex(0x777777).contrastRatio(against: hex(0x777777)) - 1) < 0.001)
    }

    @Test("matches published WCAG values for the brand pairs the audit measured")
    func knownPairs() {
        // The 2026-09-29 theming audit's table, and the old static coral it replaced.
        #expect(abs(white.contrastRatio(against: hex(0xD73812)) - 4.70) < 0.01)
        #expect(abs(hex(0xFF6A3D).contrastRatio(against: hex(0x1C1C1E)) - 5.98) < 0.01)
        #expect(abs(hex(0xD73812).contrastRatio(against: hex(0x1C1C1E)) - 3.62) < 0.01)
        #expect(abs(white.contrastRatio(against: hex(0xFF6A3D)) - 2.85) < 0.01)
        #expect(abs(hex(0xF0512F).contrastRatio(against: white) - 3.55) < 0.01)
        // #767676 is the classic "just passes AA on white" grey.
        #expect(hex(0x767676).contrastRatio(against: white) >= 4.5)
        #expect(hex(0x777777).contrastRatio(against: white) < 4.5)
    }

    @Test("HSB construction agrees with the primaries and greys")
    func hsbPrimaries() {
        #expect(close(SRGBColor(hue: 0, saturation: 1, brightness: 1), SRGBColor(red: 1, green: 0, blue: 0)))
        #expect(close(SRGBColor(hue: 1.0 / 3, saturation: 1, brightness: 1), SRGBColor(red: 0, green: 1, blue: 0)))
        #expect(close(SRGBColor(hue: 2.0 / 3, saturation: 1, brightness: 1), SRGBColor(red: 0, green: 0, blue: 1)))
        #expect(close(SRGBColor(hue: 0.4, saturation: 0, brightness: 0.5), SRGBColor(red: 0.5, green: 0.5, blue: 0.5)))
        // Hue wraps: 1.0 is red again.
        #expect(close(SRGBColor(hue: 1, saturation: 1, brightness: 1), SRGBColor(red: 1, green: 0, blue: 0)))
    }

    private func close(_ lhs: SRGBColor, _ rhs: SRGBColor) -> Bool {
        abs(lhs.red - rhs.red) < 0.0001 && abs(lhs.green - rhs.green) < 0.0001 && abs(lhs.blue - rhs.blue) < 0.0001
    }

    @Test("compositing at 0 is the background, at 1 the colour, and in between a straight mix")
    func compositing() {
        let coral = hex(0xD73812)
        #expect(coral.composited(opacity: 0, over: white) == white)
        #expect(coral.composited(opacity: 1, over: white) == coral)
        let half = black.composited(opacity: 0.5, over: white)
        #expect(abs(half.red - 0.5) < 0.0001 && abs(half.green - 0.5) < 0.0001)
    }

    @Test("the minimum over several backgrounds is the worst one")
    func minimumAgainstSeveral() {
        let coral = hex(0xFF6A3D)
        let backgrounds = [hex(0x000000), hex(0x1C1C1E), hex(0x2C2C2E)]
        let worst = coral.contrastRatio(against: hex(0x2C2C2E))
        #expect(abs(coral.minimumContrastRatio(against: backgrounds) - worst) < 0.0001)
    }

    @Test("out-of-range channels clamp into sRGB")
    func clampsChannels() {
        #expect(SRGBColor(red: 1.2, green: -0.1, blue: 0.5) == SRGBColor(red: 1, green: 0, blue: 0.5))
    }
}
