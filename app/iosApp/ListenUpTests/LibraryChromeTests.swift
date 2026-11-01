import Foundation
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

    private let day: Int64 = 86_400_000

    @Test func booksSubtitleNamesCountAndLengthInTheLargestWholeUnit() {
        #expect(LibrarySubtitle.length(count: 248, totalMs: 41 * day + 3_600_000) == "248 books · 41 days of listening")
        #expect(LibrarySubtitle.length(count: 12, totalMs: 5 * 3_600_000) == "12 books · 5 hours of listening")
        #expect(LibrarySubtitle.length(count: 3, totalMs: 7 * 60_000) == "3 books · 7 minutes of listening")
    }

    @Test func booksSubtitleSaysOneOfEachInTheSingular() {
        #expect(LibrarySubtitle.length(count: 1, totalMs: day) == "1 book · 1 day of listening")
        #expect(LibrarySubtitle.length(count: 1, totalMs: 3_600_000) == "1 book · 1 hour of listening")
        #expect(LibrarySubtitle.length(count: 1, totalMs: 60_000) == "1 book · 1 minute of listening")
    }

    /// Under a minute of audio there is no span to name: the books alone.
    @Test func booksSubtitleIsJustTheBooksUnderAMinute() {
        #expect(LibrarySubtitle.length(count: 2, totalMs: 59_999) == "2 books")
        #expect(LibrarySubtitle.length(count: 0, totalMs: 0) == "0 books")
    }

    /// While the library is still arriving with nothing yet, the strip says so; "0 books" would not.
    @Test func noSubtitleWhileAnEmptyLibraryIsArriving() {
        #expect(LibrarySubtitle.booksOrNil(count: 0, totalMs: 0, isArriving: true) == nil)
        #expect(LibrarySubtitle.booksOrNil(count: 12, totalMs: 5 * 3_600_000, isArriving: true)
            == "12 books · 5 hours of listening")
        #expect(LibrarySubtitle.booksOrNil(count: 0, totalMs: 0, isArriving: false) == "0 books")
    }

    // MARK: - Sort labels

    @Test func sortLabelsComeFromTheCatalogInTitleCase() {
        #expect(SortLabels.category(.added) == String(localized: "library.sort_added_ios"))
        #expect(SortLabels.category(.title) == String(localized: "library.sort_title"))
        #expect(SortLabels.category(.duration) == String(localized: "library.sort_duration"))
        #expect(SortLabels.direction(.title, .descending) == String(localized: "library.dir_z_to_a"))
        #expect(SortLabels.direction(.duration, .descending) == String(localized: "library.dir_longest"))
    }

    @Test func everyCategoryHasItsOwnLabel() {
        let labels = LibrarySortOptions.categories(for: .books).map(SortLabels.category)
            + LibrarySortOptions.categories(for: .series).map(SortLabels.category)
        #expect(Set(labels).count == 10)
        let blank = labels.filter(\.isEmpty)
        #expect(blank.isEmpty)
    }

    @Test func filteredEmptyCopyFollowsTheFilter() {
        #expect(LibrarySubtitle.filteredEmpty(.finished) == String(localized: "library.filtered_empty_finished"))
    }
}
