import Testing
@testable import ListenUp

/// The hero's social sentence (spec §2.10): two names a clause, listening first, each clause counting its own rest.
@Suite("Book Detail social sentence")
struct SocialSentenceTests {
    private func line(_ listening: [String], _ finished: [String]) -> ReadersSocialLine {
        ReadersSocialLine(
            listening: listening,
            finished: finished,
            avatarUserIds: Array((listening + finished).prefix(3)).map { $0.lowercased() },
            othersCount: 0
        )
    }

    @Test func twoListeningOneFinished() {
        #expect(HeroSocialLineView.sentence(line(["Maya", "Priya"], ["Tom"]))
            == "Maya and Priya are listening · Tom finished it")
    }

    @Test func moreThanTwoCountsTheRest() {
        #expect(HeroSocialLineView.sentence(line(["Maya", "Priya", "A", "B", "C"], []))
            == "Maya, Priya and 3 others are listening")
    }

    @Test func oneOtherIsSingular() {
        #expect(HeroSocialLineView.sentence(line(["Maya", "Priya", "A"], []))
            == "Maya, Priya and 1 other are listening")
    }

    @Test func onlyFinished() {
        #expect(HeroSocialLineView.sentence(line([], ["Tom"])) == "Tom finished it")
    }
}
