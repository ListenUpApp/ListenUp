import ListenupContract
import SwiftUI
@preconcurrency import Shared

/// One command in a book's context menu.
enum BookContextMenuAction: Hashable {
    /// Play, Resume with its time left, or Listen Again: whatever the book's state leads with.
    case primary
    case addToShelf
    case share
    case select
    case addToCollection
    case editDetails
}

/// What a book's menu leads with, mirroring the shared `bookMenuPrimary` rule from the card's own state:
/// a started book resumes (with its time left), a finished one is listened to again, anything else plays.
enum BookMenuPrimaryValue: Equatable {
    case play
    case resume(timeLeftMs: Int64)
    case listenAgain

    init(_ state: LibraryCardState?) {
        switch state {
        case .inProgress(_, let timeLeftMs): self = .resume(timeLeftMs: timeLeftMs)
        case .finished: self = .listenAgain
        case .notStarted, nil: self = .play
        }
    }

    /// "Play", "Resume · 6h 2m" or "Listen Again" (HIG title case).
    var label: String {
        switch self {
        case .play:
            String(localized: "book.detail_play_ios")
        case .resume(let timeLeftMs):
            String(
                format: String(localized: "library.menu_primary_with_time"),
                String(localized: "book.detail_resume_ios"),
                DurationFormatting.hoursMinutes(ms: timeLeftMs)
            )
        case .listenAgain:
            String(localized: "book.detail_listen_again_ios")
        }
    }
}

/// What a book's context menu offers, in order and in groups.
///
/// HIG, Context menus: "prioritize relevancy" and "aim to place the most frequently used menu items
/// where people are likely to encounter them first"; "you don't want more than about three groups";
/// "hide unavailable menu items, don't dim them"; destructive items go last. So the primary (Play,
/// Resume or Listen Again) leads; Add to Shelf and Share follow; Select — the way into acting on several
/// books — closes the reader's groups. An admin gets a fourth, short group last: Add to Collection and
/// Edit Details (open question 2). There is no destructive book command on iOS today, so none is listed.
///
/// Download and Mark Finished are not here: a book cell carries neither its download state nor its
/// finished state, so the menu could only offer them blind — offering Download on a book that is
/// already downloaded. Both remain on Book Detail.
enum BookContextMenuModel {
    nonisolated static func sections(
        canShare: Bool,
        canSelect: Bool,
        isAdmin: Bool = false
    ) -> [[BookContextMenuAction]] {
        var sections: [[BookContextMenuAction]] = [[.primary]]
        let keep = (canSelect ? [BookContextMenuAction.addToShelf] : []) + (canShare ? [.share] : [])
        if !keep.isEmpty { sections.append(keep) }
        if canSelect { sections.append([.select]) }
        // The collection picker and the editor both ride the screen's selection.
        if canSelect && isAdmin { sections.append([.addToCollection, .editDetails]) }
        return sections
    }
}

/// A book's share link, built from the connected server's identity — the one shape Book Detail and
/// the context menu both hand to `ShareLink`.
enum BookShareLink {
    static func url(bookId: String, instanceId: String, remoteUrl: String?) -> URL? {
        // The embedded server URL is advisory (display / future connect), so the WAN `remoteUrl` is
        // the right value, without a trailing slash.
        let trimmed = remoteUrl.map { $0.hasSuffix("/") ? String($0.dropLast()) : $0 }
        let raw = ShareLinkCodec.shared.encode(
            target: ShareTargetBook(
                bookId: BookId(value: bookId),
                serverInstanceId: instanceId,
                serverUrl: trimmed
            )
        )
        return URL(string: raw)
    }
}

/// The window's share links: the connected server's identity, read once, so every book's context
/// menu can offer Share without a round trip of its own.
@Observable
@MainActor
final class BookShareLinks {
    private struct Server: Equatable {
        let instanceId: String
        let remoteUrl: String?
    }

    private var server: Server?

    /// Reads the server identity (RPC-backed, cached by the repository). Until it lands — or when
    /// offline with nothing cached — `url(for:)` is nil and the menu leaves Share out.
    func load() async {
        guard server == nil,
              let info = try? await Dependencies.shared.instanceRepository.getServerInfoOrNull(forceRefresh: false)
        else { return }
        server = Server(instanceId: info.instanceId, remoteUrl: info.remoteUrl)
    }

    func url(for bookId: String) -> URL? {
        guard let server else { return nil }
        return BookShareLink.url(bookId: bookId, instanceId: server.instanceId, remoteUrl: server.remoteUrl)
    }
}

extension EnvironmentValues {
    /// The window's share links; nil outside the tab shell (previews, auth), where Share is left out.
    @Entry var bookShareLinks: BookShareLinks?
}

/// The items of a book's context menu, wired to actions that already exist: the player, the
/// selection's shelf picker, the share link and selection mode.
struct BookContextMenuItems: View {
    let bookId: String
    /// The screen's selection, when it has one — Add to Shelf, Select and the admin group need it.
    let selection: BookSelectionObserver?
    /// What the menu leads with; the Library passes the book's state, other screens keep Play.
    var primary: BookMenuPrimaryValue = .play

    @Environment(\.dependencies) private var deps
    @Environment(\.bookShareLinks) private var shareLinks

    var body: some View {
        let shareURL = shareLinks?.url(for: bookId)
        let sections = BookContextMenuModel.sections(
            canShare: shareURL != nil,
            canSelect: selection != nil,
            isAdmin: selection?.isAdmin == true
        )
        ForEach(sections, id: \.self) { section in
            Section {
                ForEach(section, id: \.self) { action in
                    item(action, shareURL: shareURL)
                }
            }
        }
    }

    @ViewBuilder
    private func item(_ action: BookContextMenuAction, shareURL: URL?) -> some View {
        switch action {
        case .primary:
            Button {
                deps.playerObserver.play(bookId: bookId)
            } label: {
                Label(primary.label, systemImage: "play.fill")
            }
            // The board draws the primary in the Library coral (N6).
            .tint(Color.listenUpOrange)
        case .addToShelf:
            if let selection {
                // "…": the item needs more input, a shelf (HIG, Menus).
                Button(String(localized: "library.menu_add_to_shelf_ios"), systemImage: "text.badge.plus") {
                    selection.addOneBookToShelf(bookId)
                }
            }
        case .share:
            if let shareURL {
                ShareLink(item: shareURL) {
                    // "…": the share sheet asks where (HIG, Menus), as the board and the other platforms say.
                    Label(String(localized: "library.menu_share"), systemImage: "square.and.arrow.up")
                }
            }
        case .select:
            if let selection {
                Button(String(localized: "common.select"), systemImage: "checkmark.circle") {
                    selection.enter(bookId)
                }
            }
        case .addToCollection:
            if let selection {
                Button(String(localized: "library.menu_add_to_collection_ios"), systemImage: "folder.badge.plus") {
                    selection.addOneBookToCollection(bookId)
                }
            }
        case .editDetails:
            if let selection {
                Button(String(localized: "library.menu_edit_details_ios"), systemImage: "square.and.pencil") {
                    selection.editOneBook(bookId)
                }
            }
        }
    }
}

extension View {
    /// Attaches a book's context menu, previewing `preview` above it (HIG, Context menus: "prefer a
    /// graphical preview that clarifies the target of a context menu's commands").
    func bookContextMenu<Preview: View>(
        bookId: String,
        selection: BookSelectionObserver?,
        primary: BookMenuPrimaryValue = .play,
        @ViewBuilder preview: () -> Preview
    ) -> some View {
        contextMenu {
            BookContextMenuItems(bookId: bookId, selection: selection, primary: primary)
        } preview: {
            preview()
        }
    }
}
