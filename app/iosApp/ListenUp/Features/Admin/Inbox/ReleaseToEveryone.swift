import SwiftUI

/// "Release to everyone?" — the one question asked before a held book goes out, from Book Detail
/// (one book) and from the inbox page (the selection). Spec §7's copy.
enum ReleaseToEveryone {
    static var title: String { String(localized: "admin.release_to_everyone") }
    static var confirm: String { String(localized: "admin.release") }

    /// "…find and play it." for one book, "…them." for several.
    static func message(count: Int) -> String {
        count == 1
            ? String(localized: "admin.release_to_everyone_body")
            : String(localized: "admin.release_to_everyone_body_plural")
    }

    /// The inbox's line under its title: "3 selected" while choosing what to release, otherwise how
    /// many books wait — never a release that has not happened. Mirrors Android's `inboxSubtitle`.
    static func subtitle(bookCount: Int, selectedCount: Int) -> String {
        if selectedCount > 0 {
            return String(format: String(localized: "admin.selected_count"), selectedCount)
        }
        return bookCount == 1
            ? String(format: String(localized: "admin.books_awaiting_review_count"), bookCount)
            : String(format: String(localized: "admin.books_awaiting_review_s_count"), bookCount)
    }
}

extension View {
    /// The one Release confirmation, for one book (Book Detail) or a selection (the inbox) (HIG,
    /// Alerts): Cancel leading as the cancel role; Release trailing in the default style, as the
    /// preferred action Return confirms — it can't simply be undone, but nothing is destroyed, so
    /// not destructive.
    func releaseConfirmation(isPresented: Binding<Bool>, count: Int, onRelease: @escaping () -> Void) -> some View {
        alert(ReleaseToEveryone.title, isPresented: isPresented) {
            Button(String(localized: "common.cancel"), role: .cancel) {}
            Button(ReleaseToEveryone.confirm, action: onRelease)
                .keyboardShortcut(.defaultAction)
        } message: {
            Text(ReleaseToEveryone.message(count: count))
        }
    }
}
