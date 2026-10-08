import Foundation
import Shared

/// What Series Detail offers the reader to change: Edit series and Add sub-series belong to Edit
/// metadata (admins always have it), so nothing is offered that the server would refuse.
struct SeriesEditAccess: Equatable {
    /// Edit series and Add sub-series are shown.
    let offersEditing: Bool
    /// Add sub-series needs the server: an editor offline sees it, disabled.
    let canAddSubSeriesNow: Bool

    /// Before the series loads, nothing is offered.
    static let none = SeriesEditAccess(offersEditing: false, canAddSubSeriesNow: false)
}

extension SeriesEditAccess {
    init(from ready: SeriesDetailUiStateReady) {
        self.init(
            offersEditing: ready.canEditMetadata,
            canAddSubSeriesNow: ready.canEditMetadata && ready.isOnline
        )
    }
}
