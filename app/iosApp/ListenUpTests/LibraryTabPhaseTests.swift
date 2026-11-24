import Testing
@testable import ListenUp

/// The Series, Authors and Narrators tabs decide what to show the way Books does — so during first sync or after a
/// failed load they no longer claim the library is empty.
@Suite("LibraryTabPhase")
struct LibraryTabPhaseTests {
    @Test func loadingWinsOverEverything() {
        let phase = LibraryTabPhase.resolve(isLoading: true, isArriving: true, errorMessage: "Offline.", isEmpty: true)
        #expect(phase == .loading)
    }

    @Test func aFailureComesNext() {
        let phase = LibraryTabPhase.resolve(isLoading: false, isArriving: true, errorMessage: "Offline.", isEmpty: true)
        #expect(phase == .error("Offline."))
    }

    @Test func anEmptyTabWhileTheLibraryArrivesSaysItIsArriving() {
        let phase = LibraryTabPhase.resolve(isLoading: false, isArriving: true, errorMessage: nil, isEmpty: true)
        #expect(phase == .arriving)
    }

    @Test func anEmptyTabOnceEverythingHasArrivedIsEmpty() {
        let phase = LibraryTabPhase.resolve(isLoading: false, isArriving: false, errorMessage: nil, isEmpty: true)
        #expect(phase == .empty)
    }

    @Test func aTabWithRowsShowsThem() {
        let phase = LibraryTabPhase.resolve(isLoading: false, isArriving: true, errorMessage: nil, isEmpty: false)
        #expect(phase == .content)
    }
}
