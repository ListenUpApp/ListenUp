import Foundation
import Testing
@testable import ListenUp

/// The project link used to force-unwrap `URL(string: lib.url)!`, crashing the row when a
/// library carried a malformed URL. The fix routes through `projectURL`, which returns nil
/// (so the link is omitted) instead of trapping.
@Suite("LicenseDetailView project URL")
struct LicenseDetailViewTests {
    @Test func malformedURLIsNilSoTheLinkIsOmitted() {
        #expect(LicenseDetailView.projectURL("") == nil)
    }

    @Test func wellFormedURLParses() {
        #expect(LicenseDetailView.projectURL("https://github.com/ListenUpApp/ListenUp") != nil)
    }
}
