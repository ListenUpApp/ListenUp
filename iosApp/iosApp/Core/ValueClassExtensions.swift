import SwiftUI
import Shared

// Kotlin value classes (BookId, SeriesId, etc.) are opaque in Swift. Their value
// is the Kotlin toString() — surfaced via String(describing:). These extensions
// give type-safe String accessors.
//
// `User_` is the SKIE name for the domain `User` (the `api.dto.auth.User` DTO
// keeps the bare `User`). If the compiler reports `User` for the domain model at
// build time, the SKIE collision resolved the other way — swap `User_` → `User`.

extension User_ {
    /// The user's ID as a Swift String.
    var idString: String { String(describing: id) }
}

extension BookListItem {
    /// The book's ID as a Swift String.
    var idString: String { String(describing: id) }
}

extension BookDetail {
    /// The book's ID as a Swift String.
    var idString: String { String(describing: id) }
}

extension Series {
    /// The series ID as a Swift String.
    var idString: String { String(describing: id) }
}

extension Contributor {
    /// The contributor's ID as a Swift String.
    var idString: String { String(describing: id) }
}

/// A consistent avatar colour derived from a user ID, via hue rotation.
func avatarColorForUserId(_ userId: String) -> Color {
    let hash = abs(userId.hashValue)
    let hue = Double(hash % 360) / 360.0
    return Color(hue: hue, saturation: 0.4, brightness: 0.65)
}
