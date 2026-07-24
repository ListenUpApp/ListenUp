import SwiftUI
import Shared

// Domain models that Swift consumes by id expose a Kotlin-side `idString`
// (`get() = id.value`), so Swift reads a plain `String` and never touches the
// exported value-class type — no Swift-side extension is needed. See the
// `idString` computed properties on the Kotlin domain models.

/// A consistent avatar colour derived from a user ID, via hue rotation.
///
/// Uses a deterministic FNV-1a hash, NOT `String.hashValue`: Swift seeds `hashValue` randomly
/// per process run (SipHash), so the same id would map to a different colour on every launch —
/// the avatar would visibly change each time the app starts, and colour-distinctness tests would
/// flake across CI runs. FNV-1a is stable across launches and machines.
func avatarColorForUserId(_ userId: String) -> Color {
    var hash: UInt64 = 0xcbf2_9ce4_8422_2325 // FNV-1a 64-bit offset basis
    for byte in userId.utf8 {
        hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3 // FNV prime
    }
    let hue = Double(hash % 360) / 360.0
    return Color(hue: hue, saturation: 0.4, brightness: 0.65)
}
