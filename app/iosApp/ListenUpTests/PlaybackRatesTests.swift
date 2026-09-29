import Testing
@testable import ListenUp

/// One catalogue of playback rates feeds the in-app speed menu, the lock screen's
/// `changePlaybackRateCommand`, and the CarPlay rate button — so the three can never offer
/// different speeds.
@Suite("PlaybackRates")
struct PlaybackRatesTests {
    @Test func catalogueRunsHalfSpeedToTriple() {
        #expect(PlaybackRates.catalogue == [0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0])
    }

    // MARK: - Menu options

    @Test func optionsAreTheCatalogueForACatalogueRate() {
        #expect(PlaybackRates.options(including: 1.25) == PlaybackRates.catalogue)
    }

    /// A speed set elsewhere (another client, an older build) must still show as the checked
    /// entry rather than leaving the picker with nothing selected.
    @Test func optionsKeepAnOffCatalogueRateInOrder() {
        let options = PlaybackRates.options(including: 1.1)
        #expect(options == [0.5, 0.75, 1.0, 1.1, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0])
    }

    // MARK: - Cycling (the CarPlay rate button)

    @Test func nextStepsUpTheCatalogue() {
        #expect(PlaybackRates.next(after: 1.0) == 1.25)
        #expect(PlaybackRates.next(after: 2.0) == 2.5)
    }

    @Test func nextWrapsFromTheFastestToTheSlowest() {
        #expect(PlaybackRates.next(after: 3.0) == 0.5)
    }

    @Test func nextFromAnOffCatalogueRateGoesToTheNextFasterOne() {
        #expect(PlaybackRates.next(after: 1.1) == 1.25)
    }

    // MARK: - Remote requests (changePlaybackRateCommand)

    @Test func acceptsASupportedRate() {
        #expect(PlaybackRates.accepted(1.5) == 1.5)
    }

    @Test func clampsAnOutOfRangeRequestToTheCatalogueBounds() {
        #expect(PlaybackRates.accepted(4.0) == 3.0)
        #expect(PlaybackRates.accepted(0.25) == 0.5)
    }

    /// The docs forbid a negative rate, and zero is a pause, not a speed.
    @Test func rejectsANonPositiveRate() {
        #expect(PlaybackRates.accepted(0) == nil)
        #expect(PlaybackRates.accepted(-1) == nil)
    }

    // MARK: - Formatting

    @Test func formatsWholeSpeedsWithoutDecimals() {
        #expect(PlaybackRates.format(1.0) == "1×")
        #expect(PlaybackRates.format(2.0) == "2×")
        #expect(PlaybackRates.format(3.0) == "3×")
    }

    @Test func formatsFractionalSpeedsTrimmingTrailingZeros() {
        #expect(PlaybackRates.format(0.5) == "0.5×")
        #expect(PlaybackRates.format(1.25) == "1.25×")
        #expect(PlaybackRates.format(1.75) == "1.75×")
    }
}
