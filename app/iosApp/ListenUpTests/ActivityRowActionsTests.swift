import Foundation
import Testing
@testable import ListenUp

/// One model decides an activity row's actions, so the context menu and VoiceOver's Actions rotor can
/// never offer different things (plan C7), and a row about neither book nor shelf has none (Q5).
@Suite("Activity row actions")
struct ActivityRowActionsTests {
    private func item(
        bookId: String? = "b1",
        shelfId: String? = nil,
        highlight: String? = "Piranesi",
        isYou: Bool = false
    ) -> ActivityRowItem {
        ActivityRowItem(
            id: "a1", userId: "maya", who: isYou ? "You" : "Maya Chen", predicate: "finished",
            highlight: highlight, bookId: bookId, shelfId: shelfId, occurredAt: Date(timeIntervalSince1970: 0),
            isYou: isYou, actorName: "Maya Chen"
        )
    }

    @Test func aBookRowOffersOpenViewAndAddToShelfInOrder() {
        let row = item()
        let actions = ActivityRowActions.actions(for: row)
        #expect(actions == [.openBook("b1"), .viewPerson("maya"), .addToShelf("b1")])
        #expect(actions.map { ActivityRowActions.title($0, item: row) }
            == ["Open Piranesi", "View Maya Chen", String(localized: "book.detail_add_to_shelf")])
    }

    @Test func yourOwnRowsPersonActionIsYourProfile() {
        let row = item(isYou: true)
        #expect(ActivityRowActions.title(.viewPerson("maya"), item: row)
            == String(localized: "discover.activity_your_profile_a11y"))
    }

    @Test func aShelfRowOffersTheShelfAndThePerson() {
        let row = item(bookId: nil, shelfId: "sh1", highlight: "Sci-fi")
        #expect(ActivityRowActions.actions(for: row) == [.openShelf("sh1"), .viewPerson("maya")])
        #expect(ActivityRowActions.title(.openShelf("sh1"), item: row) == "Open Sci-fi")
    }

    @Test func aJoinRowHasNoMenu() {
        #expect(ActivityRowActions.actions(for: item(bookId: nil, shelfId: nil, highlight: nil)).isEmpty)
    }

    @Test func withoutASelectionThereIsNoAddToShelf() {
        #expect(ActivityRowActions.actions(for: item(), canShelve: false) == [.openBook("b1"), .viewPerson("maya")])
    }

    @Test func theSelectionTitleIsTitleCase() {
        #expect(String(format: String(localized: "selection.n_selected_ios"), 2) == "2 Selected")
    }
}
