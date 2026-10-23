import SwiftUI

/// One chrome for every Now Playing sheet (player spec §3.8): an inline title, a close button in the sheet's top bar,
/// and content that scrolls when it can't fit.
///
/// HIG, Sheets: a sheet "with a close button" — iOS 26 draws `Button(role: .close)` in a toolbar as the glass close
/// button. HIG, Typography: at accessibility text sizes the content scrolls rather than clips, so `scrolls` is on unless
/// the content brings its own scroll view (a `List`, the chapter list).
struct PlayerSheetChrome<Content: View>: View {
    let title: String
    var scrolls = true
    @ViewBuilder let content: () -> Content

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if scrolls {
                    ScrollView { content() }
                        .scrollBounceBehavior(.basedOnSize)
                } else {
                    content()
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(role: .close) { dismiss() }
                }
            }
        }
    }
}

/// The detents a player sheet offers (HIG, Sheets): the medium detent, so Now Playing stays in view while a value is
/// adjusted, and the large one too at accessibility text sizes, where the content needs the room.
struct PlayerSheetDetents: ViewModifier {
    /// Offer the large detent at every text size: content, like the sleep list, that never fits the medium one.
    var alwaysLarge = false

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        content
            .presentationDetents(alwaysLarge ? [.medium, .large] : Self.detents(for: dynamicTypeSize))
            .presentationDragIndicator(.visible)
    }

    static func detents(for size: DynamicTypeSize) -> Set<PresentationDetent> {
        size.isAccessibilitySize ? [.medium, .large] : [.medium]
    }
}

extension View {
    /// A player sheet's detents: medium, growing a large one at accessibility sizes (or always, with `alwaysLarge`).
    func playerSheetDetents(alwaysLarge: Bool = false) -> some View {
        modifier(PlayerSheetDetents(alwaysLarge: alwaysLarge))
    }
}
