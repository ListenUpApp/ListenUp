import Foundation
import Testing
@testable import ListenUp

// #1539: the Hardcover screen's "What comes back" names the Want to Read list. The row reads its text from
// the catalog; an unknown key resolves to itself, so this fails until the key and its English exist.

@Suite("Hardcover Want to Read line")
struct HardcoverWantToReadLineTests {
    @Test("the line resolves to its English text")
    func lineResolves() {
        let line = String(localized: "hardcover.comes_back_want_to_read")
        #expect(line == "Your Want to Read list, on your To Read shelf")
    }
}
