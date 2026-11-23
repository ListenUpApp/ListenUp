import SwiftUI

/// What a Library tab shows, decided the way Books decides it: loading first, then a failure, then an empty tab
/// that is still arriving, then empty, then its rows. Series, Authors and Narrators use it so that during first
/// sync or after a failed load they no longer say "No series yet".
enum LibraryTabPhase: Equatable {
    case loading
    case error(String)
    case arriving
    case empty
    case content

    /// `isLoading`, `isArriving` and `errorMessage` describe the whole library load (`LibraryObserver`);
    /// `isEmpty` is this tab's own rows.
    static func resolve(isLoading: Bool, isArriving: Bool, errorMessage: String?, isEmpty: Bool) -> LibraryTabPhase {
        if isLoading { return .loading }
        if let errorMessage { return .error(errorMessage) }
        if isEmpty { return isArriving ? .arriving : .empty }
        return .content
    }
}

/// The loading, arriving and failed bodies a non-book Library tab shows in place of its list, in Books' words.
/// Pull to refresh works here as it does on the list.
struct LibraryTabStatusView: View {
    let phase: LibraryTabPhase
    var picker: LibrarySectionPicker?
    let onRefresh: () -> Void

    var body: some View {
        LibrarySectionState(picker: picker) {
            switch phase {
            case .loading:
                LoadingStateView(label: String(localized: "library.loading_your_library"))
            case .arriving:
                ContentUnavailableView {
                    Label(String(localized: "library.arriving_title_ios"), systemImage: "books.vertical")
                } description: {
                    Text(String(localized: "library.arriving_none_yet"))
                }
            case .error(let message):
                ContentUnavailableView {
                    Label(String(localized: "library.sync_failed"), systemImage: "exclamationmark.triangle")
                } description: {
                    Text(message)
                } actions: {
                    Button(String(localized: "common.try_again"), action: onRefresh)
                }
            case .empty, .content:
                EmptyView()
            }
        }
        .libraryRefreshable(onRefresh)
    }
}

extension View {
    /// Pull to refresh on a Library tab: the library re-syncs, and the spinner stays a moment so the pull reads
    /// as having done something (the sync itself reports through the observer).
    func libraryRefreshable(_ onRefresh: @escaping () -> Void) -> some View {
        refreshable {
            onRefresh()
            try? await Task.sleep(for: .seconds(1))
        }
    }
}
