import Testing
@testable import ListenUp

/// Home's title is the tab's name in the large title; the greeting is its subtitle, worded from the catalog (H2).
@Suite("Home title")
struct HomeTitleTests {
    @Test func theGreetingAndNameShareOneSubtitle() {
        #expect(HomeTitle.subtitle(timeOfDay: .evening, firstName: "Simon") == "Good evening, Simon")
    }

    @Test func noNameLeavesJustTheGreeting() {
        #expect(HomeTitle.subtitle(timeOfDay: .morning, firstName: nil) == "Good morning")
        #expect(HomeTitle.subtitle(timeOfDay: .night, firstName: "") == "Good night")
    }

    @Test func everyPartOfTheDayHasItsWords() {
        #expect(HomeTitle.greeting(.afternoon) == "Good afternoon")
    }
}
