import SwiftUI

/// Which form the mini player renders in the tab-view bottom accessory.
///
/// The accessory reports its placement via `\.tabViewBottomAccessoryPlacement`:
/// `.inline` (docked beside the minimized tab bar) → `.compact`; anything else
/// (`.expanded`, or no value) → `.expanded`. Resolved from a plain `Bool` so the
/// mapping is unit-testable without constructing the framework placement type.
enum MiniPlayerLayout: Equatable {
    /// Rich row: progress line, cover, title + chapter, time-remaining, play/pause.
    case expanded
    /// Compact row docked beside the minimized bar: cover, title, play/pause.
    case compact

    static func resolve(isInline: Bool) -> MiniPlayerLayout {
        isInline ? .compact : .expanded
    }
}
