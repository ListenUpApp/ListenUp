import Testing
@testable import ListenUp

@Suite("Book Detail hero and title")
struct BookDetailTitleTests {
    @Test func statsLineIsLengthYearAndAbridgement() {
        let line = BookDetailHero.statsLine(duration: "45 hr 30 min", year: 2010, isAbridged: false)
        #expect(line == "45 hr 30 min · 2010 · \(String(localized: "book.detail_unabridged"))")
    }

    @Test func statsLineSkipsAMissingYear() {
        let line = BookDetailHero.statsLine(duration: "3 hr", year: nil, isAbridged: true)
        #expect(line == "3 hr · \(String(localized: "book.detail_abridged"))")
    }

    @Test func durationIsHoursAndMinutes() {
        let text = BookDetailHero.durationText(ms: Int64((45 * 60 + 30) * 60_000))
        #expect(text.contains("45"))
        #expect(text.contains("30"))
        #expect(BookDetailHero.durationText(ms: 0).isEmpty)
    }

    @Test func inlineTitleAppearsOnlyOnceTheHeroTitleHasScrolledAway() {
        #expect(!BookDetailInlineTitle.isVisible(heroTitleMaxY: 120))
        #expect(BookDetailInlineTitle.isVisible(heroTitleMaxY: -1))
    }

    @Test func heroToneIsNeutralInLightAndSystemInDark() {
        #expect(BookDetailHeroTone.color(for: .light) != BookDetailHeroTone.color(for: .dark))
    }
}
