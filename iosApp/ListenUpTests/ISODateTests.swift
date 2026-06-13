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
}
