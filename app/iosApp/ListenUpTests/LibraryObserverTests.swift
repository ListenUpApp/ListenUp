import Testing
@testable import ListenUp

/// `LibraryObserver.apply` needs live KMP `LibraryUiState` instances, so its skip decision is pinned
/// at the pure seam it delegates to: the content lists are re-mapped only when the shared
/// ViewModel's `contentRevision` moves. A position save every ~5s of playback, or a sync tick,
/// arrives with the same revision and must leave `books` untouched.
@Suite("ContentRevisionGate")
struct ContentRevisionGateTests {
    @Test func firstRevisionApplies() {
        var gate = ContentRevisionGate()
        let applied = gate.advance(to: 1)
        #expect(applied)
        #expect(gate.applied == 1)
    }

    @Test func unchangedRevisionIsSkipped() {
        var gate = ContentRevisionGate()
        _ = gate.advance(to: 7)
        let again = gate.advance(to: 7)
        let andAgain = gate.advance(to: 7)
        #expect(!again)
        #expect(!andAgain)
    }

    @Test func newRevisionApplies() {
        var gate = ContentRevisionGate()
        _ = gate.advance(to: 7)
        let applied = gate.advance(to: 8)
        #expect(applied)
        #expect(gate.applied == 8)
    }

    /// Revision 0 is a real revision, not "none": the gate starts empty rather than at 0.
    @Test func revisionZeroStillAppliesFirst() {
        var gate = ContentRevisionGate()
        let applied = gate.advance(to: 0)
        #expect(applied)
    }
}
