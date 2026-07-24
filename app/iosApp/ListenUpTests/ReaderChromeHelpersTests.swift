import Testing
@testable import ListenUp

@Suite("Reader chrome helpers")
struct ReaderChromeHelpersTests {
    @Test func timeLeftFormatsHoursAndMinutes() {
        #expect(formatTimeLeft(remainingMs: 0) == "0m left")
        #expect(formatTimeLeft(remainingMs: 59_000) == "0m left")
        #expect(formatTimeLeft(remainingMs: 90_000) == "1m left")
        #expect(formatTimeLeft(remainingMs: 3_600_000) == "1h 0m left")
        #expect(formatTimeLeft(remainingMs: 35_460_000) == "9h 51m left")
    }

    @Test func scrubberPageForFractionIsOneBasedClamped() {
        #expect(scrubberPage(fraction: 0, pageCount: 1232) == 1)
        #expect(scrubberPage(fraction: 1, pageCount: 1232) == 1232)
        #expect(scrubberPage(fraction: 0.5, pageCount: 1000) == 500)
        #expect(scrubberPage(fraction: -1, pageCount: 10) == 1)
        #expect(scrubberPage(fraction: 2, pageCount: 10) == 10)
        #expect(scrubberPage(fraction: 0.5, pageCount: 0) == 0)
    }
}
