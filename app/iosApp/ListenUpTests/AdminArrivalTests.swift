import Foundation
import Testing
import Shared
@testable import ListenUp

/// Where Administration lands when it is opened for a reason — an approvals tap — rather than from
/// Settings: the decision waits for the screen's content, then scrolls at most once.
struct AdminArrivalTests {
    @Test func withoutAFocusItStaysAtTheTop() {
        for isSettingsLoaded in [true, false] {
            let arrival = AdminArrival.decide(
                focus: nil, registrationPolicy: .approvalQueue, isSettingsLoaded: isSettingsLoaded
            )
            #expect(arrival == .stay)
        }
    }

    /// The server settings sections above load separately; scrolling before they arrive would leave the
    /// pending registrations pushed back down the moment they do.
    @Test func itWaitsForTheSettingsAboveBeforeScrolling() {
        let arrival = AdminArrival.decide(
            focus: .pendingRegistrations, registrationPolicy: .approvalQueue, isSettingsLoaded: false
        )
        #expect(arrival == .waiting)
    }

    @Test func anApprovalQueueScrollsToThePendingRegistrations() {
        let arrival = AdminArrival.decide(
            focus: .pendingRegistrations, registrationPolicy: .approvalQueue, isSettingsLoaded: true
        )
        #expect(arrival == .scroll(to: .pendingRegistrations))
    }

    /// Open or closed registration hides the section, so there is nothing to scroll to.
    @Test func aPolicyThatHidesTheSectionStaysAtTheTop() {
        for policy in [RegistrationPolicy.open, .closed] {
            let arrival = AdminArrival.decide(
                focus: .pendingRegistrations, registrationPolicy: policy, isSettingsLoaded: true
            )
            #expect(arrival == .stay)
        }
    }

    @Test func anUnfocusedDestinationStillEncodesAsItAlwaysHas() throws {
        let data = try JSONEncoder().encode(AdminDestination())
        #expect(String(bytes: data, encoding: .utf8) == "{}")
        #expect(try JSONDecoder().decode(AdminDestination.self, from: Data("{}".utf8)) == AdminDestination())
    }
}
