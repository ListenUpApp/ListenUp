import Testing
@testable import ListenUp

/// Pins `DurationFormatting`'s two helpers to the exact string shapes the deleted inline
/// formatters produced. Doubles as the regression guard for the output-preserving dedup claim:
/// every replaced call site must keep rendering byte-identical strings.
struct DurationFormattingTests {
    // MARK: - hoursMinutes

    @Test func hoursMinutesDropsHoursWhenZero() {
        #expect(DurationFormatting.hoursMinutes(ms: 54 * 60_000) == "54m")
    }

    @Test func hoursMinutesShowsHoursAndMinutes() {
        #expect(DurationFormatting.hoursMinutes(ms: Int64((15 * 60 + 54)) * 60_000) == "15h 54m")
    }

    @Test func hoursMinutesZeroIsZeroMinutes() {
        #expect(DurationFormatting.hoursMinutes(ms: 0) == "0m")
    }

    @Test func hoursMinutesClampsNegativeToZero() {
        #expect(DurationFormatting.hoursMinutes(ms: -1) == "0m")
    }

    // MARK: - clock

    @Test func clockZeroIsZeroColonZeroZero() {
        #expect(DurationFormatting.clock(ms: 0) == "0:00")
    }

    @Test func clockSubMinutePadsSeconds() {
        #expect(DurationFormatting.clock(ms: 9_000) == "0:09")
    }

    @Test func clockWithHoursUsesFullForm() {
        #expect(DurationFormatting.clock(ms: Int64((1 * 3600) + (5 * 60) + 9) * 1000) == "1:05:09")
    }

    @Test func clockWithoutHoursUsesMinuteSecondForm() {
        #expect(DurationFormatting.clock(ms: Int64((5 * 60) + 9) * 1000) == "5:09")
    }
}
