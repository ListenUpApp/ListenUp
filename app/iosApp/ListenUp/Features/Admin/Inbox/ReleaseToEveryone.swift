import Foundation

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
}
