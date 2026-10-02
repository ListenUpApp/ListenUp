import SwiftUI

/// The collection-visibility lock: the documents badge's recipe (material, `Radius.s`, an 11pt
/// glyph) with SF Symbol `lock.fill` in the label colour — "access is limited", neutral, never
/// orange (the documents badge's colour) and never the inbox's amber *Held*. Top-leading; in Select
/// mode it steps right of the selection circle. Reads the shell's set from the environment, so a
/// card needs only the book id.
struct RestrictedMarker: ViewModifier {
    let bookId: String
    var isSelecting = false
    var compact = false
    /// Held wins the corner: the inbox's *Held* badge owns a held book's cover.
    var isHeld = false

    @Environment(\.restrictedBooks) private var restrictedBooks

    func body(content: Content) -> some View {
        content.overlay(alignment: .topLeading) {
            if Self.shows(isHeld: isHeld, isRestricted: restrictedBooks?.isRestricted(bookId) == true) {
                Image(systemName: "lock.fill")
                    .font(.system(size: compact ? 9 : 11, weight: .semibold)) // decorative fixed size
                    .foregroundStyle(.primary)
                    .padding(compact ? Spacing.xxs : Spacing.xs)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
                    .padding(compact ? Spacing.xxs : Spacing.xs)
                    .offset(x: Self.leadingOffset(isSelecting: isSelecting))
                    .accessibilityLabel(String(localized: "library.restricted_a11y"))
            }
        }
    }

    /// Held wins the corner: a held book never wears the lock, whatever its collections.
    nonisolated static func shows(isHeld: Bool, isRestricted: Bool) -> Bool {
        !isHeld && isRestricted
    }

    /// Clear of the 22pt selection circle and its padding (8 + 22 + 6).
    nonisolated static func leadingOffset(isSelecting: Bool) -> CGFloat {
        isSelecting ? 36 : 0
    }

    /// A card whose children are folded into one VoiceOver label ends with why the lock is there.
    nonisolated static func label(_ base: String, isRestricted: Bool) -> String {
        isRestricted ? "\(base). \(String(localized: "library.restricted_a11y"))" : base
    }
}

extension View {
    /// Draws the lock on this cover when `bookId` is restricted.
    func restrictedMarker(
        bookId: String,
        isSelecting: Bool = false,
        compact: Bool = false,
        isHeld: Bool = false
    ) -> some View {
        modifier(RestrictedMarker(bookId: bookId, isSelecting: isSelecting, compact: compact, isHeld: isHeld))
    }
}
