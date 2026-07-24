import Testing
@testable import ListenUp

/// Pins that `MergeCandidate` snapshots a bridged `ContributorCandidate` into native values
/// (id/name), so the merge picker feeds SwiftUI a value type — never a re-bridged Kotlin object
/// (per the iOS re-bridging rule). Constructed via the memberwise init (the bridged-init path
/// needs live KMP state, pinned at the pure seam per the ContributorRow convention).
@Suite("MergeCandidate")
struct MergeCandidateTests {
    @Test func differentIdProducesDifferentCandidate() {
        let a = MergeCandidate(id: "c-1", name: "Brandon Sanderson")
        let b = MergeCandidate(id: "c-2", name: "Brandon Sanderson")
        #expect(a != b)
    }

    @Test func sameValuesAreEqualAndIdentifiable() {
        let a = MergeCandidate(id: "c-1", name: "Robert Jordan")
        let b = MergeCandidate(id: "c-1", name: "Robert Jordan")
        #expect(a == b)
        #expect(a.id == "c-1")
    }
}
