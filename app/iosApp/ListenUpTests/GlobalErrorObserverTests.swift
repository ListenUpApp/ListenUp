import Foundation
import Testing
@testable import ListenUp

/// What the app-level error alert says. A server fault names its reference so a screenshot leads to the server's
/// log line; every other error, the app's own faults included, says exactly its own words.
@Suite("GlobalErrorObserver")
struct GlobalErrorObserverTests {
    @Test func aServerFaultNamesTheHeadOfItsCorrelationId() {
        let text = GlobalErrorObserver.alertText(
            code: "INTERNAL_ERROR",
            message: "Something went wrong on the server.",
            correlationId: "1a2b3c4d-5e6f-7a8b"
        )
        #expect(text == "Something went wrong on the server. Reference 1a2b3c4d.")
    }

    @Test func aServerFaultWithoutACorrelationIdSaysItsOwnWords() {
        let text = GlobalErrorObserver.alertText(
            code: "INTERNAL_ERROR",
            message: "Something went wrong on the server.",
            correlationId: nil
        )
        #expect(text == "Something went wrong on the server.")
    }

    @Test func anAppSideFaultSaysItHappenedInTheApp() {
        let text = GlobalErrorObserver.alertText(
            code: "CLIENT_UNEXPECTED",
            message: "Something went wrong in the app.",
            correlationId: nil
        )
        #expect(text == "Something went wrong in the app.")
    }
}
