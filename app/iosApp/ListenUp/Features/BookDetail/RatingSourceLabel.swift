import Foundation
import Shared

/// [ExternalRatingSource]'s display name, shared by the Book Detail rating breakdown sheet and the
/// admin Rating Sources list — mirrors Android's `ratingSourceLabel`. `.unknown` never reaches
/// either surface (both read from rows the repository has already filtered it out of), but the
/// switch still needs a branch, so it falls back to the raw case name.
extension ExternalRatingSource {
    var displayName: String {
        switch self {
        case .audible: return String(localized: "rating.source_audible")
        case .hardcover: return String(localized: "rating.source_hardcover")
        case .goodreads: return String(localized: "rating.source_goodreads")
        case .unknown: return "\(self)"
        }
    }
}
