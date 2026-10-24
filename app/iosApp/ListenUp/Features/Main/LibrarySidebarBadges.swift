import SwiftUI

/// The iPad sidebar's badges: each Library section's count, and where the held-for-review count sits.
///
/// While the sidebar shows, every section entry carries its count as a trailing number (spec §3.1.5; HIG, Sidebars: a
/// row can show a count, as Mail's mailboxes do — SwiftUI draws a sidebar tab's count through `.badge`), and the held
/// count rides the Server › Inbox entry. Hide the sidebar and iPadOS collapses the Library `TabSection` into one item
/// in the floating tab bar — it does NOT lift a child tab's badge onto that item (checked on the iPadOS 26.5
/// simulator), and the Inbox entry is sidebar-only — so the held count moves to the section itself, and the section
/// counts step aside. HIG, Tab bars: a badge on a tab tells people new information is waiting in that view — it has to
/// sit on a tab they can actually see.
struct LibrarySidebarBadges: Equatable {
    /// The badge on the Library `TabSection` — what the collapsed tab bar shows.
    let section: Int
    /// Each section entry's count — what the shown sidebar shows.
    let entries: LibrarySectionCountsValue
    /// The badge on the Server › Inbox entry.
    let inbox: Int

    func entry(_ section: LibraryTab) -> Int { entries.count(for: section) }

    /// The badges for these section `counts` and `heldCount` held books, given whether the tab bar is listing sections
    /// (`EnvironmentValues.isTabBarShowingSections`, true while the sidebar is shown).
    static func forPlacement(
        counts: LibrarySectionCountsValue,
        heldCount: Int,
        showingSections: Bool
    ) -> LibrarySidebarBadges {
        showingSections
            ? LibrarySidebarBadges(section: 0, entries: counts, inbox: heldCount)
            : LibrarySidebarBadges(section: heldCount, entries: .zero, inbox: 0)
    }
}

/// Whether the sidebar lists its sections (and with them the counts and Server › Inbox).
///
/// SwiftUI's `isTabBarShowingSections` is true while the sidebar is shown, but on the iOS 27 sidebar-first placement it
/// answers false even with the sidebar on screen (iPhone Duo, open, iOS 27.1: `tabBarPlacement` reports `bottomBar`),
/// which left the open Duo's sidebar without counts or the admin's inbox. So where the shell has placed the sidebar
/// first for a regular-by-regular window, the sidebar is the surface and the environment's answer is not asked.
enum SidebarSections {
    static func areListed(tabBarSaysShowing: Bool, usesSidebar: Bool, placesSidebarFirst: Bool) -> Bool {
        tabBarSaysShowing || (usesSidebar && placesSidebarFirst)
    }
}

extension EnvironmentValues {
    /// Whether the sidebar lists its sections, as the shell decided it (`SidebarSections.areListed`). Set by
    /// `MainTabView` for every tab's content, which cannot rely on `isTabBarShowingSections` alone.
    @Entry var sidebarListsSections = false
}

/// Where the admin inbox is reached on iPad, so the held books are always one tap from a badge that says so.
///
/// HIG, Tab bars: "You can display a badge … on a tab to indicate that there's new or updated information in the
/// section that warrants a person's attention" — the badge sits on the tab whose section leads to the held books. And
/// "the tab bar's convertible sidebar-style appearance can provide access to content that people use less frequently":
/// Server › Inbox is sidebar content, so it never becomes a tab of its own in the collapsed bar.
/// https://developer.apple.com/design/human-interface-guidelines/tab-bars
enum InboxPlacement {
    /// Whether the sidebar's Server › Inbox entry exists: for an admin, while the sidebar lists sections. With the
    /// sidebar hidden the whole `TabSection` would otherwise collapse into a stray "Server" tab.
    static func showsServerSection(isAdmin: Bool, showingSections: Bool) -> Bool {
        isAdmin && showingSections
    }

    /// Whether the Books grid carries its own inbox entry. The compact tab bar's Library has no sidebar, and a hidden
    /// sidebar has no Server › Inbox, so the grid is the way in; while the sidebar lists Server › Inbox it is not.
    /// Selecting books turns the grid into a picking surface, so never then.
    static func showsGridEntry(showsSectionPicker: Bool, showingSections: Bool, isSelecting: Bool) -> Bool {
        !isSelecting && (showsSectionPicker || !showingSections)
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
