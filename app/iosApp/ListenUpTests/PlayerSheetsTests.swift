import Testing
@testable import ListenUp

@Suite("Player sheet and menu formatting")
struct PlayerSheetsTests {
    // MARK: - Sleep duration formatting

    @Test func formatsSubHourDurationsInMinutes() {
        #expect(SleepTimerOption.formatDuration(15) == "15 min")
        #expect(SleepTimerOption.formatDuration(45) == "45 min")
    }

    @Test func formatsOneHour() {
        #expect(SleepTimerOption.formatDuration(60) == "1 hour")
    }

    @Test func formatsMultipleHours() {
        #expect(SleepTimerOption.formatDuration(120) == "2 hours")
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
