import Testing
import CoreGraphics
@testable import ListenUp

struct ContributorColumnsTests {
    @Test func columnCountFlowsWithWidth() {
        #expect(ContributorColumns.columnCount(availableWidth: 390) == 1)
        #expect(ContributorColumns.columnCount(availableWidth: 760) == 2)
        #expect(ContributorColumns.columnCount(availableWidth: 1100) == 3)
        #expect(ContributorColumns.columnCount(availableWidth: 2000) == 3)
        #expect(ContributorColumns.columnCount(availableWidth: 200) == 1)
    }
    @Test func columnCountRespectsCustomBounds() {
        #expect(ContributorColumns.columnCount(availableWidth: 1000, minColumnWidth: 500, maxColumns: 4) == 2)
        #expect(ContributorColumns.columnCount(availableWidth: 5000, minColumnWidth: 500, maxColumns: 4) == 4)
    }
}
