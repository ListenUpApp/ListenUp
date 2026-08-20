import Testing
@testable import ListenUp

/// Pins `SeriesSequenceFormatting` to the same cases as the shared Kotlin `formatSeriesSequence`.
///
/// The rule is mirrored in Swift rather than called across the Swift Export seam (the same trade
/// `BookEditFormatting.tagLabel` makes), so nothing but this test stops the two drifting. Every case
/// below has a twin in `SeriesSequenceTest` on the Kotlin side.
struct SeriesSequenceFormattingTests {
    @Test func wholeNumbersDropTheDecimalTail() {
        #expect(SeriesSequenceFormatting.label(1.0) == "1")
        #expect(SeriesSequenceFormatting.label(10.0) == "10")
        #expect(SeriesSequenceFormatting.label(0.0) == "0")
    }

    @Test func realFractionsSurvive() {
        #expect(SeriesSequenceFormatting.label(1.5) == "1.5")
        #expect(SeriesSequenceFormatting.label(0.5) == "0.5")
        #expect(SeriesSequenceFormatting.label(2.25) == "2.25")
    }

    @Test func absentPositionHasNoLabel() {
        #expect(SeriesSequenceFormatting.label(nil as Double?) == nil)
    }
}
