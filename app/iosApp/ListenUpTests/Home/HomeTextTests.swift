import Foundation
import Testing
@testable import ListenUp

/// The words Home's cards and hero say (spec §2.3, §2.5, §8.2), as pure functions.
@Suite("Home text")
struct HomeTextTests {
    @Test func aCardIsOneLabelWithAuthorPercentAndTimeLeft() {
        let label = HomeCardText.label(
            title: "Babel", author: "R. F. Kuang", narrators: nil, percent: 11, remainingMs: 67_200_000
        )
        #expect(label == "Babel, by R. F. Kuang, 11 percent listened, 18 hr, 40 min left")
    }

    @Test func aWideCardAddsWhoReadsIt() {
        let label = HomeCardText.label(
            title: "Babel", author: "R. F. Kuang", narrators: "Chris Lew Kum Hoi", percent: 11, remainingMs: 67_200_000
        )
        #expect(label.contains("Read by Chris Lew Kum Hoi"))
    }

    @Test func aBookWithNoAuthorDoesNotSayByNobody() {
        let label = HomeCardText.label(title: "Babel", author: "", narrators: nil, percent: 11, remainingMs: 67_200_000)
        #expect(!label.contains("by ,"))
        #expect(label.hasPrefix("Babel, 11 percent"))
    }
}
