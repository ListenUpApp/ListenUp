import Foundation

/// Identifiable wrapper so a transient error string drives `.alert(item:)`.
struct MessageAlert: Identifiable {
    let message: String
    var id: String { message }
}
