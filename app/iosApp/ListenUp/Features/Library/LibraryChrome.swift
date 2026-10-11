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

/// The Books subtitle ("248 books · All · Title") and the filtered-empty copy, pure for testing.
enum LibrarySubtitle {
    static func books(count: Int, filter: BookStatusFilter, sortLabel: String) -> String {
        let books = count == 1
            ? String(format: String(localized: "library.book_count"), count)
            : String(format: String(localized: "library.book_count_plural"), count)
        return [books, LibraryStatusOptions.label(filter), sortLabel].joined(separator: " · ")
    }

    static func filteredEmpty(_ filter: BookStatusFilter) -> String {
        switch filter {
        case .inProgress: String(localized: "library.filtered_empty_in_progress")
        case .notStarted: String(localized: "library.filtered_empty_not_started")
        case .finished: String(localized: "library.filtered_empty_finished")
        case .all: ""
        }
    }
}

/// The Library's filter and sort menu: the toolbar's one control for the section on screen.
///
/// `Picker`s inside a `Menu` (HIG, Menus: "a menu can … let people choose one option from a set,
/// displaying a checkmark next to the current choice") — the system draws the checkmarks and the
/// selected state VoiceOver reads. The Books section leads with a "Show" group of reading-state
/// filters, each with its whole-library count (board `Library-iPhone-FilterMenu`).
struct LibrarySortMenu: View {
    let section: LibraryTab
    let sortState: SortState
    let onCategorySelected: (SortCategory) -> Void
    let onDirectionToggle: () -> Void
    let ignoreTitleArticles: Bool
    let onToggleIgnoreArticles: () -> Void
    /// The Books view's status filter; nil in the other sections, which have none.
    var statusFilter: BookStatusFilter?
    var statusCounts: LibraryStatusCounts = .zero
    var onStatusFilterSelected: (BookStatusFilter) -> Void = { _ in }

    var body: some View {
        Menu {
            if let statusFilter {
                Section(String(localized: "library.status_filters_label")) {
                    Picker(String(localized: "library.status_filters_label"), selection: statusBinding(statusFilter)) {
                        ForEach(LibraryStatusOptions.filters, id: \.self) { filter in
                            VStack {
                                Text(LibraryStatusOptions.label(filter))
                                Text("\(statusCounts.count(for: filter))")
                            }
                            .tag(filter)
                        }
                    }
                    .pickerStyle(.inline)
                }
            }
            Section(String(localized: "library.sort_by")) {
                Picker(String(localized: "library.sort_by"), selection: categoryBinding) {
                    ForEach(LibrarySortOptions.categories(for: section), id: \.self) { category in
                        Text(category.label).tag(category)
                    }
                }
                .pickerStyle(.inline)
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
            if statusFilter != nil {
                Label(String(localized: "library.filter_and_sort"), systemImage: "line.3.horizontal.decrease")
            } else {
                Label(String(localized: "library.sort"), systemImage: "arrow.up.arrow.down")
            }
        }
        .accessibilityValue(accessibilityValue)
        .haptic(.selectionTick, trigger: sortState)
        .haptic(.selectionTick, trigger: statusFilter)
    }

    /// "In Progress, Title" for Books; the sort alone elsewhere.
    private var accessibilityValue: String {
        guard let statusFilter else { return sortState.category.label }
        return "\(LibraryStatusOptions.label(statusFilter)), \(sortState.category.label)"
    }

    private func statusBinding(_ current: BookStatusFilter) -> Binding<BookStatusFilter> {
        Binding(get: { current }, set: { if $0 != current { onStatusFilterSelected($0) } })
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
