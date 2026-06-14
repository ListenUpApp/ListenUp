import Testing
@testable import ListenUp

@Suite("PlayerSheets formatting & snapping")
struct PlayerSheetsTests {
    private let speeds: [Float] = [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

    // MARK: - Speed snapping

    @Test func snapsToExactCatalogueValue() {
        #expect(SpeedPickerSheet.snap(1.0, to: speeds) == 1.0)
    }

    @Test func snapsUpToNearestSpeed() {
        #expect(SpeedPickerSheet.snap(1.2, to: speeds) == 1.25)
    }

    @Test func snapsDownToNearestSpeed() {
        #expect(SpeedPickerSheet.snap(1.1, to: speeds) == 1.0)
    }

    @Test func snapsValuesBelowFloorToMinimum() {
        #expect(SpeedPickerSheet.snap(0.3, to: speeds) == 0.5)
    }

    @Test func snapsValuesAboveCeilingToMaximum() {
        #expect(SpeedPickerSheet.snap(4.0, to: speeds) == 3.0)
    }

    @Test func bridgesWideGapToNearestNeighbour() {
        // 2.0 and 2.5 are 0.5 apart; 2.2 is closer to 2.0, 2.3 to 2.5.
        #expect(SpeedPickerSheet.snap(2.2, to: speeds) == 2.0)
        #expect(SpeedPickerSheet.snap(2.3, to: speeds) == 2.5)
    }

    // MARK: - Speed formatting

    @Test func formatsWholeSpeedsWithoutDecimals() {
        #expect(SpeedPickerSheet.formatSpeed(1.0) == "1×")
        #expect(SpeedPickerSheet.formatSpeed(2.0) == "2×")
        #expect(SpeedPickerSheet.formatSpeed(3.0) == "3×")
    }

    @Test func formatsFractionalSpeedsTrimmingTrailingZeros() {
        #expect(SpeedPickerSheet.formatSpeed(0.5) == "0.5×")
        #expect(SpeedPickerSheet.formatSpeed(1.25) == "1.25×")
        #expect(SpeedPickerSheet.formatSpeed(1.75) == "1.75×")
    }

    // MARK: - Sleep duration formatting

    @Test func formatsSubHourDurationsInMinutes() {
        #expect(SleepTimerSheet.formatDuration(15) == "15 min")
        #expect(SleepTimerSheet.formatDuration(45) == "45 min")
    }

    @Test func formatsOneHour() {
        #expect(SleepTimerSheet.formatDuration(60) == "1 hour")
    }

    @Test func formatsMultipleHours() {
        #expect(SleepTimerSheet.formatDuration(120) == "2 hours")
    }
}
