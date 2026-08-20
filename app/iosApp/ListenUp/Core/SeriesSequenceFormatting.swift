import Foundation

/// Renders a series position for display: `1.0` reads as `"1"`, `1.5` stays `"1.5"`.
///
/// The shared model stores the position as a number (it is sorted on, and a text column sorted book
/// 10 before book 2), but a whole `Double` interpolates as `"1.0"`, and "Book 1.0" is not how anyone
/// writes it.
///
/// Mirrors the shared `formatSeriesSequence` rather than calling it, following the same reasoning as
/// `BookEditFormatting.tagLabel`: a two-line display rule is not worth depending on the Swift Export
/// seam for. `SeriesSequenceFormattingTests` pins it to the same cases as the Kotlin
/// `SeriesSequenceTest`, so the two cannot drift silently.
enum SeriesSequenceFormatting {
    /// Formats [sequence] for display, or `nil` when the book has no position in the series.
    ///
    /// Takes a plain `Double?`, not a `KotlinDouble?`: Swift Export bridges the shared
    /// `BookSeries.sequence` straight to `Double?`, so no unwrapping is needed and this file
    /// stays pure Foundation — testable without the framework.
    static func label(_ sequence: Double?) -> String? {
        guard let sequence else { return nil }
        // `truncatingRemainder` rather than `Int(sequence)`: a position is small, but converting to
        // Int to test for wholeness would trap on a value outside Int's range instead of just
        // formatting it.
        if sequence.truncatingRemainder(dividingBy: 1) == 0 {
            return String(Int64(sequence))
        }
        return String(sequence)
    }
}
