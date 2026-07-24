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
    @Test func balancedColumnsSingleColumnKeepsOrder() {
        let result = ContributorColumns.balancedColumns([0, 1, 2, 3], weight: { _ in 1 }, columns: 1)
        #expect(result == [[0, 1, 2, 3]])
    }
    @Test func balancedColumnsEvenSplit() {
        let result = ContributorColumns.balancedColumns([0, 1, 2, 3, 4, 5], weight: { _ in 1 }, columns: 3)
        #expect(result == [[0, 1], [2, 3], [4, 5]])
    }
    @Test func balancedColumnsByWeight() {
        #expect(ContributorColumns.balancedColumns([10, 1, 1, 10], weight: { $0 }, columns: 2) == [[10, 1], [1, 10]])
    }
    @Test func balancedColumnsMoreColumnsThanItems() {
        let result = ContributorColumns.balancedColumns([0, 1], weight: { _ in 1 }, columns: 3)
        #expect(result == [[0], [1], []])
        #expect(result.flatMap { $0 } == [0, 1])
    }
}
