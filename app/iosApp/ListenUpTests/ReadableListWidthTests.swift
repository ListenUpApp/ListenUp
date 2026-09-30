import CoreGraphics
import Testing
@testable import ListenUp

@Suite("Readable list width")
struct ReadableListWidthTests {
    @Test func phoneKeepsTheSystemMargin() {
        #expect(ReadableListWidth.margin(containerWidth: 402, maxWidth: 640) == nil)
    }

    @Test func justOverTheCapStillKeepsTheSystemMargin() {
        // (670 - 640) / 2 = 15 — narrower than the system's own margin, so the system wins.
        #expect(ReadableListWidth.margin(containerWidth: 670, maxWidth: 640) == nil)
    }

    @Test func wideWindowCentresTheColumn() {
        #expect(ReadableListWidth.margin(containerWidth: 1376, maxWidth: 640) == 368)
        #expect(ReadableListWidth.margin(containerWidth: 1032, maxWidth: 720) == 156)
    }
}
