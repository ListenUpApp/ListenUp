import SwiftUI
@preconcurrency import Shared

/// How a Library screen frames itself for the shell tab that hosts it.
///
/// The compact tab bar has one Library tab, so the screen switches its four sections with a
/// segmented control (HIG, Segmented controls: "consider a segmented control to switch between
/// closely related subviews"). In the iPad sidebar each section is its own entry, so the sidebar is
/// the switcher and the screen is titled by the section it shows — a second, redundant switcher
/// under a title that says only "Library" would leave people unsure which entry they chose.
struct LibraryChrome: Equatable {
    let showsSectionPicker: Bool

    init(tab: ShellTab) {
        if case .librarySection = tab {
            showsSectionPicker = false
        } else {
            showsSectionPicker = true
        }
    }

    /// The navigation title: "Library" above the picker, the section's own name in the sidebar.
    func title(section: LibraryTab) -> String {
        showsSectionPicker ? String(localized: "common.library") : section.title
    }
}

/// What the Library toolbar's Sort menu offers for each section.
enum LibrarySortOptions {
    static func categories(for section: LibraryTab) -> [SortCategory] {
        switch section {
        case .books: [.title, .author, .duration, .year, .added, .rating, .listenerRating, .series]
        case .series: [.name, .bookCount, .added]
        case .authors, .narrators: [.name, .bookCount]
        }
    }

    /// "Ignore A, An, The" only means something while Books sort by Title or Series by Name — the
    /// sorts whose order (and whose section letters) the shared article setting changes.
    static func offersArticleToggle(section: LibraryTab, category: SortCategory) -> Bool {
        switch section {
        case .books: category == .title
        case .series: category == .name
        case .authors, .narrators: false
        }
    }
}

/// The Library's Sort menu: the toolbar's one sort control for the section on screen.
///
/// A `Picker` inside a `Menu` (HIG, Menus: "a menu can … let people choose one option from a set,
/// displaying a checkmark next to the current choice") — the system draws the checkmarks and the
/// selected state VoiceOver reads, where the old floating pill hand-drew them.
struct LibrarySortMenu: View {
    let section: LibraryTab
    let sortState: SortState
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void
    let ignoreTitleArticles: Bool
    let onToggleIgnoreArticles: () -> Void

    var body: some View {
        Menu {
            Picker(String(localized: "library.sort_by"), selection: categoryBinding) {
                ForEach(LibrarySortOptions.categories(for: section), id: \.self) { category in
                    Text(category.label).tag(category)
                }
            }
            Picker(String(localized: "library.sort_order"), selection: directionBinding) {
                Label(String(localized: "library.sort_ascending"), systemImage: "arrow.up")
                    .tag(SortDirection.ascending)
                Label(String(localized: "library.sort_descending"), systemImage: "arrow.down")
                    .tag(SortDirection.descending)
            }
            if LibrarySortOptions.offersArticleToggle(section: section, category: sortState.category) {
                Toggle(String(localized: "library.ignore_articles"), isOn: articlesBinding)
            }
        } label: {
            Label(String(localized: "library.sort"), systemImage: "arrow.up.arrow.down")
        }
        .accessibilityValue(sortState.category.label)
        .haptic(.selectionTick, trigger: sortState)
    }

    private var categoryBinding: Binding<SortCategory> {
        Binding(get: { sortState.category }, set: { onCategorySelected($0) })
    }

    private var directionBinding: Binding<SortDirection> {
        Binding(
            get: { sortState.direction },
            set: { if $0 != sortState.direction { onDirectionToggle() } }
        )
    }

    private var articlesBinding: Binding<Bool> {
        Binding(
            get: { ignoreTitleArticles },
            set: { if $0 != ignoreTitleArticles { onToggleIgnoreArticles() } }
        )
    }
}
