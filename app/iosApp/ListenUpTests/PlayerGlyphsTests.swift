import Testing
@testable import ListenUp

@Suite("PlayerGlyphs skip symbols")
struct PlayerGlyphsTests {
    @Test func forwardUsesDedicatedSymbolForSupportedIntervals() {
        #expect(PlayerGlyphs.skipForward(seconds: 5) == "goforward.5")
        #expect(PlayerGlyphs.skipForward(seconds: 10) == "goforward.10")
        #expect(PlayerGlyphs.skipForward(seconds: 15) == "goforward.15")
        #expect(PlayerGlyphs.skipForward(seconds: 30) == "goforward.30")
        #expect(PlayerGlyphs.skipForward(seconds: 45) == "goforward.45")
        #expect(PlayerGlyphs.skipForward(seconds: 60) == "goforward.60")
        #expect(PlayerGlyphs.skipForward(seconds: 75) == "goforward.75")
        #expect(PlayerGlyphs.skipForward(seconds: 90) == "goforward.90")
    }

    @Test func backwardUsesDedicatedSymbolForSupportedIntervals() {
        #expect(PlayerGlyphs.skipBackward(seconds: 5) == "gobackward.5")
        #expect(PlayerGlyphs.skipBackward(seconds: 10) == "gobackward.10")
        #expect(PlayerGlyphs.skipBackward(seconds: 30) == "gobackward.30")
        #expect(PlayerGlyphs.skipBackward(seconds: 90) == "gobackward.90")
    }

    @Test func forwardFallsBackToGenericForUnsupportedIntervals() {
        #expect(PlayerGlyphs.skipForward(seconds: 7) == "goforward")
        #expect(PlayerGlyphs.skipForward(seconds: 20) == "goforward")
        #expect(PlayerGlyphs.skipForward(seconds: 0) == "goforward")
    }

    @Test func backwardFallsBackToGenericForUnsupportedIntervals() {
        #expect(PlayerGlyphs.skipBackward(seconds: 7) == "gobackward")
        #expect(PlayerGlyphs.skipBackward(seconds: 20) == "gobackward")
        #expect(PlayerGlyphs.skipBackward(seconds: 0) == "gobackward")
    }
}
