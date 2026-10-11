import Testing
@testable import ListenUp
@preconcurrency import Shared

/// The matching observers close their shared ViewModels when their screen lets them go: iOS has no `ViewModelStore`
/// to call `onCleared`, so each observer hands its ViewModel to its `FlowBridge`, which clears it on release. Two
/// halves pin that:
/// - each observer is released with its screen — nothing it binds keeps it alive, so its bridge is released;
/// - every screen gets its own ViewModel from Koin's `factory`, so one screen closing its ViewModel can never cancel
///   another screen's (the CarPlay Library bug, #1192).
@Suite("Matching observers close their ViewModels")
@MainActor
struct MatchObserverLifetimeTests {
    @Test func theBookMatchObserverIsReleasedWithItsScreen() {
        weak var released: BookMatchObserver?
        do {
            let observer = BookMatchObserver(viewModel: Dependencies.shared.createBookMatchViewModel(bookId: "book-1"))
            released = observer
            #expect(released != nil)
        }
        #expect(released == nil)
    }

    @Test func thePersonMatchObserverIsReleasedWithItsScreen() {
        weak var released: PersonMatchObserver?
        do {
            let observer = PersonMatchObserver(
                viewModel: Dependencies.shared.createPersonMatchViewModel(contributorId: "person-1")
            )
            released = observer
            #expect(released != nil)
        }
        #expect(released == nil)
    }

    @Test func theReceiptObserverIsReleasedWithItsPage() {
        weak var released: MatchReceiptObserver?
        do {
            let observer = MatchReceiptObserver(
                viewModel: Dependencies.shared.createMatchReceiptViewModel(subjectId: "book-1")
            )
            released = observer
            #expect(released != nil)
        }
        #expect(released == nil)
    }

    @Test func everyScreenGetsItsOwnViewModelSoClosingOneLeavesTheOthers() {
        let deps = Dependencies.shared
        #expect(deps.createBookMatchViewModel(bookId: "book-1") !== deps.createBookMatchViewModel(bookId: "book-1"))
        #expect(
            deps.createPersonMatchViewModel(contributorId: "person-1")
                !== deps.createPersonMatchViewModel(contributorId: "person-1")
        )
        #expect(
            deps.createMatchReceiptViewModel(subjectId: "book-1")
                !== deps.createMatchReceiptViewModel(subjectId: "book-1")
        )
    }

    @Test func closingAViewModelTwiceIsHarmless() {
        let viewModel = Dependencies.shared.createMatchReceiptViewModel(subjectId: "book-1")
        KoinHelper.shared.closeViewModel(viewModel: viewModel)
        KoinHelper.shared.closeViewModel(viewModel: viewModel)
    }
}
