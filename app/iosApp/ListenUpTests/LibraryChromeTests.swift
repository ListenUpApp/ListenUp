import Testing
@preconcurrency import Shared
@testable import ListenUp

/// The Library screen's chrome: which switcher it shows, what its title names, and what its toolbar
/// Sort menu offers for each section.
@MainActor
@Suite("Library chrome")
struct LibraryChromeTests {
    // MARK: - Section switcher and title

    @Test func theCompactLibraryTabSwitchesSectionsWithTheSegmentedPicker() {
        let chrome = LibraryChrome(tab: .library)
        #expect(chrome.showsSectionPicker)
        #expect(chrome.title(section: .series) == String(localized: "common.library"))
    }

    @Test func aSidebarSectionEntryNamesItsSectionAndLeavesSwitchingToTheSidebar() {
        let chrome = LibraryChrome(tab: .librarySection(.narrators))
        #expect(!chrome.showsSectionPicker)
        #expect(chrome.title(section: .narrators) == LibraryTab.narrators.title)
    }

    @Test func thePickerAndTheSidebarDriveTheSameSection() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: false)
        shell.selectLibrarySection(.authors, from: .library)
        #expect(shell.librarySection == .authors)
        #expect(shell.selectedTab == .home)

        shell.adaptToLayout(usesSidebar: true)
        shell.selectedTab = .librarySection(.series)
        shell.selectLibrarySection(.books, from: .librarySection(.series))
        #expect(shell.selectedTab == .librarySection(.books))
        #expect(shell.librarySection == .books)
    }

    // MARK: - Sort menu

    @Test func eachSectionOffersItsOwnSortCategories() {
        #expect(LibrarySortOptions.categories(for: .books)
            == [.title, .author, .duration, .year, .added, .rating, .listenerRating, .series])
        #expect(LibrarySortOptions.categories(for: .series) == [.name, .bookCount, .added])
        #expect(LibrarySortOptions.categories(for: .authors) == [.name, .bookCount])
        #expect(LibrarySortOptions.categories(for: .narrators) == [.name, .bookCount])
    }

    @Test func theArticleToggleShowsOnlyForAnAlphabeticalTitleSort() {
        #expect(LibrarySortOptions.offersArticleToggle(section: .books, category: .title))
        #expect(!LibrarySortOptions.offersArticleToggle(section: .books, category: .author))
        #expect(LibrarySortOptions.offersArticleToggle(section: .series, category: .name))
        #expect(!LibrarySortOptions.offersArticleToggle(section: .series, category: .added))
        #expect(!LibrarySortOptions.offersArticleToggle(section: .authors, category: .name))
        #expect(!LibrarySortOptions.offersArticleToggle(section: .narrators, category: .name))
    }

    // MARK: - Books subtitle and filtered-empty copy

    @Test func booksSubtitleNamesCountFilterAndSort() {
        let subtitle = LibrarySubtitle.books(count: 248, filter: .all, sortLabel: "Title")
        #expect(subtitle == "248 books · All · Title")
    }

    @Test func booksSubtitleSaysOneBookInTheSingular() {
        #expect(LibrarySubtitle.books(count: 1, filter: .finished, sortLabel: "Title") == "1 book · Finished · Title")
    }

    @Test func filteredEmptyCopyFollowsTheFilter() {
        #expect(LibrarySubtitle.filteredEmpty(.finished) == String(localized: "library.filtered_empty_finished"))
    }
}
