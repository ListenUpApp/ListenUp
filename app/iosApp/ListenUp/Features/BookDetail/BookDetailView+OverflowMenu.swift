import SwiftUI

/// Which of the More menu's questions is open. One `@State` for the three, so `BookDetailView`'s body stays under
/// SwiftLint's cap.
enum BookDetailMenuConfirmation: Identifiable {
    case markNotStarted, restart, deleteFromServer

    var id: Self { self }
}

/// Book Detail's More menu (spec §2.7), drawn from the shared rule (`bookDetailMoreMenu`): Share first, in an unnamed
/// section; then Progress, Edit and Admin, each a named `Section`; then Delete from Server… alone in a last unnamed
/// section with the destructive role (HIG, Pull-down buttons and Menus: group related items, destructive items at the
/// end). A held book has no menu: the toolbar shows the Held pill, and its actions are the held card's.
///
/// Split out of `BookDetailView.swift`, which sits at SwiftLint's type-body cap. The presentation state stays on the
/// view, because `@State` storage cannot live in an extension.
@MainActor
extension BookDetailView {

    /// The toolbar for the book on screen: the More menu, or — for a held book — the Held pill. While the book loads
    /// nothing is offered: whether it is held is not yet known.
    @ToolbarContentBuilder
    var overflowMenu: some ToolbarContent {
        let toolbar = BookDetailLayout.toolbar(for: loadedLayout)
        if toolbar == .overflowMenu {
            fullOverflowMenu
        } else if toolbar == .heldPill {
            // HIG, Toolbars: a status item may sit in the bar; it is not a control.
            ToolbarItem(placement: .topBarTrailing) { HeldBadge() }
        }
    }

    /// The layout of the book on screen, or `nil` until it has loaded — and after an error, when there is no book.
    private var loadedLayout: BookDetailLayout? {
        guard let observer else { return nil }
        return BookDetailLayout.loaded(isLoading: observer.isLoading, hasError: observer.error != nil,
                                       isHeld: observer.isHeld)
    }

    @ToolbarContentBuilder
    private var fullOverflowMenu: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            if let observer, !observer.menu.isEmpty {
                Menu {
                    ForEach(observer.menu, id: \.self) { section in
                        // An unnamed group (Share, Delete) is set off by its divider alone.
                        if let heading = section.heading {
                            Section(heading.title) { menuButtons(section, observer) }
                        } else {
                            Section { menuButtons(section, observer) }
                        }
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .accessibilityLabel(String(localized: "book.detail_more_actions"))
                }
                .menuConfirmations($menuConfirmation, observer: observer)
            }
        }
    }

    private func menuButtons(_ section: BookDetailMenuSection, _ observer: BookDetailObserver) -> some View {
        ForEach(section.commands, id: \.self) { command in menuButton(command, observer) }
    }

    @ViewBuilder
    private func menuButton(_ command: BookDetailMenuCommand, _ observer: BookDetailObserver) -> some View {
        if command == .share, let url = observer.shareURL {
            ShareLink(
                item: url,
                subject: Text(observer.title),
                message: Text(String(format: String(localized: "common.share_book_text"), observer.title))
            ) {
                Label(command.title, systemImage: command.systemImage)
            }
        } else {
            Button(role: command.isDestructive ? .destructive : nil) {
                perform(command, observer)
            } label: {
                Label(command.title, systemImage: command.systemImage)
            }
            .disabled(
                (command.waitsOnProgress && observer.isChangingProgress)
                    || (command == .deleteFromServer && observer.isDeletingBook)
            )
        }
    }

    private func perform(_ command: BookDetailMenuCommand, _ observer: BookDetailObserver) {
        switch command {
        case .share: break // a ShareLink, above
        case .markFinished: showMarkFinished = true
        case .restart: menuConfirmation = .restart
        case .markNotStarted: menuConfirmation = .markNotStarted
        case .editDetails: showEdit = true
        case .matchDetails: matchTarget = BookMatchTarget(bookId: bookId)
        case .editChapters: showChapterEditor = true
        case .addToCollection: observer.openCollectionPicker()
        case .deleteFromServer:
            observer.clearDeleteError()
            menuConfirmation = .deleteFromServer
        }
    }
}

private extension View {
    /// The More menu's three questions, keyed off one `BookDetailMenuConfirmation`.
    ///
    /// Delete is an alert, not the confirmation dialog the two progress resets use (Simon's call: the HIG wins on
    /// iOS). HIG, Alerts — "when people take an uncommon destructive action that they can't undo, it's important to
    /// display an alert in case they initiated the action accidentally." Cancel is the safe way out and never the
    /// default; the verb is Delete Book, not OK. A refusal arrives as `ErrorAlertCenter`'s alert, so this one is never
    /// re-presented for it — two alerts contending for one presentation slot is how one of them is lost.
    func menuConfirmations(
        _ confirmation: Binding<BookDetailMenuConfirmation?>,
        observer: BookDetailObserver
    ) -> some View {
        self
            .confirmationDialog(
                String(localized: "book.detail_mark_not_started_title"),
                isPresented: confirmation.isPresenting(.markNotStarted),
                titleVisibility: .visible
            ) {
                // Destructive inside its own question: it loses a position, so it asks in red (HIG, Menus).
                Button(String(localized: "book.detail_mark_as_not_started"), role: .destructive) {
                    observer.discardProgress()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                Text(String(localized: "book.detail_mark_not_started_body"))
            }
            .confirmationDialog(
                String(localized: "book.detail_restart_title"),
                isPresented: confirmation.isPresenting(.restart),
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_restart"), role: .destructive) { observer.restartBook() }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                Text(String(localized: "book.detail_restart_body"))
            }
            .alert(
                BookDeletion.title(bookTitle: observer.title),
                isPresented: confirmation.isPresenting(.deleteFromServer)
            ) {
                Button(BookDeletion.confirm, role: .destructive) { observer.deleteBook() }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                let tracked = observer.trackedForDeletion
                Text(BookDeletion.message(
                    fileCount: tracked.fileCount,
                    formattedSize: BookSize.label(tracked.bytes)
                ))
            }
    }
}

private extension Binding where Value == BookDetailMenuConfirmation? {
    /// True while `which` is the open question; setting false closes it.
    func isPresenting(_ which: BookDetailMenuConfirmation) -> Binding<Bool> {
        Binding<Bool>(
            get: { wrappedValue == which },
            set: { if !$0, wrappedValue == which { wrappedValue = nil } }
        )
    }
}
