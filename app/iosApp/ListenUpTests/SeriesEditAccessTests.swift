import Testing
import Shared
@testable import ListenUp

/// Pins how the shared `SeriesDetailUiState.Ready` maps to what Series Detail offers: Edit series and
/// Add sub-series follow Edit metadata, and Add sub-series is reachable only online.
struct SeriesEditAccessTests {
    private func ready(canEditMetadata: Bool, isOnline: Bool = true) -> SeriesDetailUiStateReady {
        SeriesDetailUiStateReady(
            seriesId: "s1",
            seriesName: "Mistborn",
            seriesDescription: nil,
            seriesAuthors: [],
            seriesNarrator: nil,
            coverPath: nil,
            featuredBookId: nil,
            totalDuration: ExportedKotlinPackages.kotlin.time.Duration.Companion.shared.ZERO,
            books: [],
            bookProgress: [:],
            finishedBookIds: [],
            resumeTarget: nil,
            ancestors: [],
            childSeries: [],
            bookSections: [],
            resumeBook: nil,
            canEditMetadata: canEditMetadata,
            isOnline: isOnline
        )
    }

    @Test func aReaderWhoMayEditIsOfferedEditing() {
        let access = SeriesEditAccess(from: ready(canEditMetadata: true))
        #expect(access.offersEditing == true)
        #expect(access.canAddSubSeriesNow == true)
    }

    @Test func aReaderWhoMayNotEditIsOfferedNothing() {
        let access = SeriesEditAccess(from: ready(canEditMetadata: false))
        #expect(access.offersEditing == false)
        #expect(access.canAddSubSeriesNow == false)
    }

    @Test func anEditorOfflineIsOfferedEditingButCannotAddASubSeriesYet() {
        let access = SeriesEditAccess(from: ready(canEditMetadata: true, isOnline: false))
        #expect(access.offersEditing == true)
        #expect(access.canAddSubSeriesNow == false)
    }

    @Test func beforeTheSeriesLoadsNothingIsOffered() {
        #expect(SeriesEditAccess.none.offersEditing == false)
    }
}
