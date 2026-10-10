import Foundation
import Observation

/// Which window's root view is in front.
///
/// One per process. The root of every window reports when its scene becomes active, and anything
/// the root presents for the whole app — the invite sheet, the error alert — asks here whether this
/// is the window to present it. The tab shells keep their own bookkeeping for book links and
/// notification taps (`DeepLinkRouter`, `PushTapRouter`); this is the same rule (`FrontWindow`) for
/// what is presented before a shell exists.
@Observable
@MainActor
final class FrontRootWindow {
    static let shared = FrontRootWindow()

    private(set) var frontID: UUID?

    init() {}

    func became(_ id: UUID) { frontID = id }

    func wentAway(_ id: UUID) {
        if frontID == id { frontID = nil }
    }

    func isFront(_ id: UUID) -> Bool {
        FrontWindow.receives(id, frontSceneID: frontID)
    }
}
