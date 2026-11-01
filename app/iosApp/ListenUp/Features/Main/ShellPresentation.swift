import SwiftUI

/// A sheet that holds work (an editor, a form) and is presented by the window's shell, above the tabs.
///
/// The tab view is given a new identity when the window flips between sidebar and tab bar (a fold, a Split View
/// resize — see `SidebarFirstTabBar`), which rebuilds everything inside the tabs and dismisses any sheet presented
/// from in there. Presented from the shell instead, an open editor and its unsaved draft survive the rebuild.
enum ShellSheet: Identifiable, Equatable {
    case bookEdit(bookId: String)
    case chapterEditor(bookId: String)
    case contributorEdit(contributorId: String)
    case seriesEdit(seriesId: String)
    case shelfForm(shelfId: String?)
    case editProfile
    case createInvite

    var id: String {
        switch self {
        case .bookEdit(let bookId): "bookEdit.\(bookId)"
        case .chapterEditor(let bookId): "chapterEditor.\(bookId)"
        case .contributorEdit(let contributorId): "contributorEdit.\(contributorId)"
        case .seriesEdit(let seriesId): "seriesEdit.\(seriesId)"
        case .shelfForm(let shelfId): "shelfForm.\(shelfId ?? "new")"
        case .editProfile: "editProfile"
        case .createInvite: "createInvite"
        }
    }
}

/// A full-screen cover presented by the window's shell, for the same reason as ``ShellSheet``.
enum ShellCover: Identifiable, Equatable {
    case documentReader(ReaderDocument)

    var id: String {
        switch self {
        case .documentReader(let document): "documentReader.\(document.id)"
        }
    }
}

/// What a ``ShellSheet`` shows.
struct ShellSheetContent: View {
    let sheet: ShellSheet
    let shell: MainShellModel

    var body: some View {
        switch sheet {
        case .bookEdit(let bookId):
            BookEditView(bookId: bookId)
        case .chapterEditor(let bookId):
            ChapterEditorView(bookId: bookId)
        case .contributorEdit(let contributorId):
            // A merge leaves this contributor behind: the page under the sheet becomes the survivor's.
            ContributorEditView(
                contributorId: contributorId,
                onMergedInto: { shell.retarget(to: ContributorDestination(id: $0)) }
            )
        case .seriesEdit(let seriesId):
            SeriesEditView(seriesId: seriesId, onMergedInto: { shell.retarget(to: SeriesDestination(id: $0)) })
        case .shelfForm(let shelfId):
            CreateEditShelfView(shelfId: shelfId)
        case .editProfile:
            EditProfileView()
        case .createInvite:
            CreateInviteView()
        }
    }
}

/// What a ``ShellCover`` shows.
struct ShellCoverContent: View {
    let cover: ShellCover
    let shell: MainShellModel

    var body: some View {
        switch cover {
        case .documentReader(let document):
            DocumentReaderView(document: document, onDone: { shell.dismissPresentation() })
        }
    }
}

extension EnvironmentValues {
    /// Present a sheet from the window's shell, above the tabs, so it survives the tab view being rebuilt on a fold or
    /// resize. Provided by `MainTabView`; anything that holds unsaved work presents through this, never a tab-local
    /// `.sheet`.
    @Entry var presentShellSheet: (ShellSheet) -> Void = { _ in }

    /// Present a full-screen cover from the window's shell; see ``presentShellSheet``.
    @Entry var presentShellCover: (ShellCover) -> Void = { _ in }
}
