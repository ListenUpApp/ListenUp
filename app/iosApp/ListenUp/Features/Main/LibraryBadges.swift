import SwiftUI

/// Where the iPad shell shows the held-for-review count.
///
/// The sidebar lists the four Library sections as entries; the count rides Books, the section the
/// inbox entry heads. Hide the sidebar and iPadOS collapses the whole `TabSection` into one Library
/// item in the floating tab bar — and it does NOT lift a child tab's badge onto that item (checked
/// on the iPadOS 26.5 simulator: the Books badge simply vanished). So the count moves to the section
/// itself while the bar is collapsed, and back to Books when the sidebar returns. Never both: a
/// shown sidebar would put it on the section header too. HIG, Tab bars: a badge on a tab tells people
/// new information is waiting in that view — it has to sit on a tab they can actually see.
struct LibraryBadges: Equatable {
    /// The badge on the Library `TabSection` — what the collapsed tab bar shows.
    let section: Int
    /// The badge on the Books entry — what the shown sidebar shows.
    let books: Int

    /// The badges for `heldCount` held books, given whether the tab bar is listing sections
    /// (`EnvironmentValues.isTabBarShowingSections`, true while the sidebar is shown).
    static func forPlacement(heldCount: Int, showingSections: Bool) -> LibraryBadges {
        showingSections
            ? LibraryBadges(section: 0, books: heldCount)
            : LibraryBadges(section: heldCount, books: 0)
    }
}

/// Reports `EnvironmentValues.isTabBarShowingSections` up to the shell. SwiftUI publishes it only
/// inside a tab's content, while the badges are set on the `TabView` itself, so every tab's stack
/// carries this and writes the value back — whichever tab is on screen keeps it current.
struct TabBarSectionsProbe: ViewModifier {
    @Binding var isShowingSections: Bool
    @Environment(\.isTabBarShowingSections) private var isTabBarShowingSections

    func body(content: Content) -> some View {
        content.onChange(of: isTabBarShowingSections, initial: true) { _, showing in
            isShowingSections = showing
        }
    }
}
