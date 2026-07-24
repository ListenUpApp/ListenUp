import Testing
@testable import ListenUp

@Suite("CoverAccessibility")
struct CoverAccessibilityTests {
    @Test func titleAndAuthorJoinWithComma() {
        #expect(CoverAccessibility.label(title: "The Way of Kings", author: "Brandon Sanderson")
            == "The Way of Kings, Brandon Sanderson")
    }

    @Test func titleOnly() {
        #expect(CoverAccessibility.label(title: "Untitled", author: nil) == "Untitled")
    }

    @Test func authorOnlyReturnsAuthor() {
        #expect(CoverAccessibility.label(title: nil, author: "Anon") == "Anon")
    }

    @Test func neitherReturnsNil() {
        #expect(CoverAccessibility.label(title: nil, author: nil) == nil)
    }

    @Test func blankAndWhitespaceTreatedAsAbsent() {
        #expect(CoverAccessibility.label(title: "  ", author: "") == nil)
        #expect(CoverAccessibility.label(title: "Dune", author: "   ") == "Dune")
    }
}
