import Foundation
import Testing
@testable import ListenUp

@Suite("ISODate")
struct ISODateTests {
    @Test func roundTripsAValidDate() {
        let date = ISODate.parse("1947-09-21")
        #expect(date != nil)
        #expect(ISODate.format(date!) == "1947-09-21")
    }

    @Test func emptyStringParsesToNil() {
        #expect(ISODate.parse("") == nil)
    }

    @Test func malformedStringParsesToNil() {
        #expect(ISODate.parse("not-a-date") == nil)
        #expect(ISODate.parse("2024-13-40") == nil)
    }

    /// A `.date` `DatePicker` hands back local-midnight; formatting it must yield the same
    /// day (a UTC formatter would shift it back a day east of UTC). Regression for that bug.
    @Test func formatsLocalMidnightToTheSameDay() {
        var comps = DateComponents()
        comps.year = 2024
        comps.month = 3
        comps.day = 15
        let localMidnight = Calendar.current.date(from: comps)!
        #expect(ISODate.format(localMidnight) == "2024-03-15")
    }
}
