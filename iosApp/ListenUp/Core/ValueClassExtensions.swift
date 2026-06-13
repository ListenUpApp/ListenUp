import SwiftUI
@preconcurrency import Shared

// Domain models that Swift consumes by id expose `idString` from Kotlin (the
// value class is unboxed at the SKIE boundary), so no Swift-side extension is
// needed. See the `idString` computed properties on the Kotlin domain models.

/// A consistent avatar colour derived from a user ID, via hue rotation.
func avatarColorForUserId(_ userId: String) -> Color {
    let hash = abs(userId.hashValue)
    let hue = Double(hash % 360) / 360.0
    return Color(hue: hue, saturation: 0.4, brightness: 0.65)
}
