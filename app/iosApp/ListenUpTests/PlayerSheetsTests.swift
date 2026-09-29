import Foundation
import Testing
@testable import ListenUp

@Suite("Player sheet and menu formatting")
struct PlayerSheetsTests {
    // MARK: - Sleep duration formatting

    // The labels come from Foundation's duration formatter, so every locale gets its own words and
    // its own plural rules — the hard-coded "15 min" / "1 hour" were English everywhere.
    private static let english = Locale(identifier: "en_US")

    @Test func formatsSubHourDurationsInMinutes() {
        #expect(SleepTimerOption.formatDuration(15, locale: Self.english) == "15 minutes")
        #expect(SleepTimerOption.formatDuration(45, locale: Self.english) == "45 minutes")
    }

    @Test func formatsOneHourInTheSingular() {
        #expect(SleepTimerOption.formatDuration(60, locale: Self.english) == "1 hour")
    }

    @Test func formatsMultipleHoursInThePlural() {
        #expect(SleepTimerOption.formatDuration(120, locale: Self.english) == "2 hours")
    }

    @Test(arguments: [
        ("de_DE", 15, "15 Minuten"), ("de_DE", 60, "1 Stunde"), ("de_DE", 120, "2 Stunden"),
        ("fr_FR", 60, "1 heure"), ("fr_FR", 120, "2 heures"),
        ("pl_PL", 120, "2 godziny"), ("pl_PL", 300, "5 godzin")
    ])
    func formatsInTheReadersLanguageWithItsPluralRules(locale: String, minutes: Int, expected: String) {
        #expect(SleepTimerOption.formatDuration(minutes, locale: Locale(identifier: locale)) == expected)
    }

    // MARK: - Boost formatting

    @Test func formatsZeroBoostAsOff() {
        #expect(BoostPickerSheet.formatBoost(0) == "Off")
    }

    @Test func formatsPositiveBoostWithDbSuffix() {
        #expect(BoostPickerSheet.formatBoost(6) == "+6 dB")
    }

    @Test func formatsMaximumBoost() {
        #expect(BoostPickerSheet.formatBoost(12) == "+12 dB")
    }

    @Test func pillFormatDropsTheUnitSoTheControlNeverTruncates() {
        #expect(BoostPickerSheet.formatBoostPill(6) == "+6")
        #expect(BoostPickerSheet.formatBoostPill(12) == "+12")
    }

    /// The pill carries no label of its own, so "Off" never said WHAT was off. Spelling the unit
    /// out at the floor — the state most listeners see — is also what lets the bare "+6" above
    /// read as decibels. The sheet keeps "Off": there the heading supplies the context.
    @Test func pillFormatNamesTheUnitAtTheFloor() {
        #expect(BoostPickerSheet.formatBoostPill(0) == "0 dB")
    }
}
