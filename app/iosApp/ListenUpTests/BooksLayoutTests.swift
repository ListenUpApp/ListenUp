import Testing
import CoreGraphics
@testable import ListenUp

struct BooksLayoutTests {
    @Test func regularWidthUsesPadMetrics() {
        let layout = BooksLayout.forRegularWidth(true)
        #expect(layout.sideMargin == 36)
        #expect(layout.gridSpacing == 24)
        #expect(layout.usesInlineSort == true)
        #expect(layout.showsScrubber == false)
    }
    @Test func compactWidthKeepsPhoneMetrics() {
        let layout = BooksLayout.forRegularWidth(false)
        #expect(layout.sideMargin == 16)
        #expect(layout.gridSpacing == 16)
        #expect(layout.usesInlineSort == false)
        #expect(layout.showsScrubber == true)
    }
}
