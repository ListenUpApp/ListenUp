import Testing
@testable import ListenUp

/// Home's title is the tab's name in the system large title; the greeting is its subtitle.
@Suite("Home title")
struct HomeTitleTests {
    @Test func theGreetingAndNameShareOneSubtitle() {
        #expect(HomeTitle.subtitle(greeting: "Good evening", userName: "Simon") == "Good evening, Simon")
    }

    @Test func aMissingNameLeavesJustTheGreeting() {
        #expect(HomeTitle.subtitle(greeting: "Good evening", userName: "") == "Good evening")
    }

    @Test func aMissingGreetingLeavesJustTheName() {
        #expect(HomeTitle.subtitle(greeting: "", userName: "Simon") == "Simon")
        #expect(HomeTitle.subtitle(greeting: "", userName: "") == "")
    }
}
