import Testing
import Observation

/// Observable state the tests below mutate (or deliberately never mutate).
@Observable
private final class Flag {
    var isSet = false
}

@Suite("awaitObservation")
struct AwaitObservationTests {
    @Test func resumesWhenTheObservedStateMutates() async {
        let flag = Flag()
        Task { flag.isSet = true }
        await awaitObservation { flag.isSet }
        #expect(flag.isSet)
    }

    @Test func returnsAtOnceWhenTheConditionAlreadyHolds() async {
        let flag = Flag()
        flag.isSet = true
        await awaitObservation { flag.isSet }
        #expect(flag.isSet)
    }

    /// The watchdog: a mutation that never comes ends the wait with a recorded issue instead of
    /// parking the test forever. Reaching the line after the wait is the proof it didn't hang.
    @Test func aMutationThatNeverComesEndsAtTheWatchdog() async {
        let flag = Flag()
        await withKnownIssue {
            await awaitObservation(timeout: .milliseconds(200)) { flag.isSet }
        }
        #expect(flag.isSet == false)
    }
}
