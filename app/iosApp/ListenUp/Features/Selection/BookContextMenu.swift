import SwiftUI
@preconcurrency import Shared

/// One command in a book's context menu.
enum BookContextMenuAction: Hashable {
    case play
    case addToShelf
    case share
    case select
}

/// What a book's context menu offers, in order and in groups.
///
/// HIG, Context menus: "prioritize relevancy" and "aim to place the most frequently used menu items
/// where people are likely to encounter them first"; "you don't want more than about three groups";
/// "hide unavailable menu items, don't dim them"; destructive items go last. So Play leads; Add to
/// Shelf and Share follow; Select — the way into acting on several books — closes. There is no
/// destructive book command on iOS today, so none is listed.
///
/// Download and Mark Finished are not here: a book cell carries neither its download state nor its
/// finished state, so the menu could only offer them blind — offering Download on a book that is
/// already downloaded. Both remain on Book Detail.
enum BookContextMenuModel {
    nonisolated static func sections(canShare: Bool, canSelect: Bool) -> [[BookContextMenuAction]] {
        var sections: [[BookContextMenuAction]] = [[.play]]
        let keep = (canSelect ? [BookContextMenuAction.addToShelf] : []) + (canShare ? [.share] : [])
        if !keep.isEmpty { sections.append(keep) }
        if canSelect { sections.append([.select]) }
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
    /// The screen's selection, when it has one — Add to Shelf and Select need it.
    let selection: BookSelectionObserver?

    @Environment(\.dependencies) private var deps
    @Environment(\.bookShareLinks) private var shareLinks

    var body: some View {
        let shareURL = shareLinks?.url(for: bookId)
        let sections = BookContextMenuModel.sections(canShare: shareURL != nil, canSelect: selection != nil)
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
        case .play:
            Button(String(localized: "common.play"), systemImage: "play.fill") {
                deps.playerCoordinator.play(bookId: bookId)
            }
        case .addToShelf:
            if let selection {
                Button(String(localized: "book.detail_add_to_shelf"), systemImage: "text.badge.plus") {
                    selection.addOneBookToShelf(bookId)
                }
            }
        case .share:
            if let shareURL {
                ShareLink(item: shareURL) {
                    Label(String(localized: "common.share"), systemImage: "square.and.arrow.up")
                }
            }
        case .select:
            if let selection {
                Button(String(localized: "common.select"), systemImage: "checkmark.circle") {
                    selection.enter(bookId)
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
        @ViewBuilder preview: () -> Preview
    ) -> some View {
        contextMenu {
            BookContextMenuItems(bookId: bookId, selection: selection)
        } preview: {
            preview()
        }
    }
}
