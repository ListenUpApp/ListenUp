import Testing
@testable import ListenUp
@preconcurrency import Shared

/// The matching observers close their shared ViewModels when their screen lets them go: iOS has no `ViewModelStore`
/// to call `onCleared`, so each observer's `isolated deinit` calls `close()`. Two halves pin that:
/// - each observer is released with its screen — nothing it binds keeps it alive, so its deinit runs;
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
        viewModel.close()
        viewModel.close()
    }
}
