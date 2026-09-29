import SwiftUI
import Shared

// Domain models that Swift consumes by id expose a Kotlin-side `idString`
// (`get() = id.value`), so Swift reads a plain `String` and never touches the
// exported value-class type — no Swift-side extension is needed. See the
// `idString` computed properties on the Kotlin domain models.

/// A consistent avatar colour derived from a user ID — its `AvatarPalette` slot, which hashes the
/// id with FNV-1a (stable across launches, unlike `String.hashValue`).
func avatarColorForUserId(_ userId: String) -> Color {
    AvatarPalette.fill(forKey: userId)
}
