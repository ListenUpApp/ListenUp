import SwiftUI

/// How an auth screen lays itself out, derived purely from the horizontal size class.
/// `compact` = full-screen iPhone flow; `regular` = centered card (iPad / future Mac).
enum AuthLayoutMode {
    case compact
    case regular

    init(horizontalSizeClass: UserInterfaceSizeClass?) {
        self = horizontalSizeClass == .regular ? .regular : .compact
    }
}
