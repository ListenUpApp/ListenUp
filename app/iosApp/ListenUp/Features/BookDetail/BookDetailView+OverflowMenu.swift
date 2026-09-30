import SwiftUI

/// Book Detail's overflow menu — every action that is not the hero's own.
///
/// Split out of `BookDetailView.swift` the way `PlayerCoordinator+Chapters` was split out of its
/// own file: that struct sits at SwiftLint's 400-line body cap, and the menu is the largest
/// self-contained block in it. The presentation state stays on the view, because `@State` storage
/// cannot live in an extension.
@MainActor
extension BookDetailView {

    @ToolbarContentBuilder
    var overflowMenu: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) {
            Menu {
                Button {
                    showEdit = true
                } label: {
                    Label(String(localized: "book.detail_edit_book"), systemImage: "pencil")
                }

                Button {
                    showChapterEditor = true
                } label: {
                    Label(String(localized: "chapter_editor.title"), systemImage: "list.bullet.indent")
                }

                Button {
                    showMetadataMatch = true
                } label: {
                    Label(String(localized: "metadata.match_on_audible"), systemImage: "sparkles")
                }

                Button {
                    observer?.openShelfPicker()
                } label: {
                    Label(String(localized: "book.detail_add_to_shelf"), systemImage: "text.badge.plus")
                }

                if observer?.isAdmin == true {
                    Button {
                        observer?.openCollectionPicker()
                    } label: {
                        Label(
                            String(localized: "book.detail_add_to_collection"),
                            systemImage: "rectangle.stack.badge.plus"
                        )
                    }
                }

                if let shareURL = observer?.shareURL {
                    ShareLink(
                        item: shareURL,
                        subject: Text(observer?.title ?? ""),
                        message: Text(String(
                            format: String(localized: "common.share_book_text"),
                            observer?.title ?? ""
                        ))
                    ) {
                        Label(String(localized: "common.share"), systemImage: "square.and.arrow.up")
                    }
                }

                if observer?.startedAtMs != nil || observer?.isComplete == true {
                    Button {
                        showRestartConfirmation = true
                    } label: {
                        Label(
                            String(localized: "book.detail_restart"),
                            systemImage: "backward.end"
                        )
                    }

                    Button(role: .destructive) {
                        showDiscardConfirmation = true
                    } label: {
                        Label(
                            String(localized: "book.detail_mark_as_not_started"),
                            systemImage: "arrow.counterclockwise"
                        )
                    }
                }

                // Admin-only, last, and in its own section: everything above it can be undone and
                // this cannot. Removing files from a self-hosted library is not a member's call —
                // the server refuses it too.
                if observer?.isAdmin == true {
                    Section {
                        Button(role: .destructive) {
                            observer?.clearDeleteError()
                            showDeleteBookConfirmation = true
                        } label: {
                            Label(
                                String(
                                    format: String(localized: "common.delete_name"),
                                    String(localized: "common.book")
                                ),
                                systemImage: "trash"
                            )
                        }
                        .disabled(observer?.isDeletingBook == true)
                    }
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .confirmationDialog(
                String(localized: "book.detail_mark_not_started_prompt"),
                isPresented: $showDiscardConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_mark_as_not_started"), role: .destructive) {
                    observer?.discardProgress()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
            .confirmationDialog(
                String(localized: "book.detail_restart_prompt"),
                isPresented: $showRestartConfirmation,
                titleVisibility: .visible
            ) {
                Button(String(localized: "book.detail_restart"), role: .destructive) {
                    observer?.restartBook()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            }
            // An alert, not the confirmation dialog the two progress resets use: HIG, Alerts — "when
            // people take an uncommon destructive action that they can't undo, it's important to
            // display an alert in case they initiated the action accidentally." The message is the
            // whole consequence (the folder, what's tracked in it, every device), so it gets the
            // modal that is read rather than the sheet of choices that is scanned. Cancel is the
            // safe way out and is never the default; the verb is "Delete forever", not "OK".
            // A refusal arrives as `ErrorAlertCenter`'s alert, so this one is never re-presented
            // for it — two alerts contending for one presentation slot is how one of them is lost.
            .alert(
                BookDeletion.title(bookTitle: observer?.title ?? ""),
                isPresented: $showDeleteBookConfirmation
            ) {
                Button(String(localized: "book.detail_delete_book_confirm"), role: .destructive) {
                    observer?.deleteBook()
                }
                Button(String(localized: "common.cancel"), role: .cancel) {}
            } message: {
                let tracked = observer?.trackedForDeletion
                    ?? BookDeletion.Tracked(audioFileSizes: [], documentSizes: [])
                Text(BookDeletion.message(
                    fileCount: tracked.fileCount,
                    formattedSize: StorageFormat.byteSize(tracked.bytes)
                ))
            }
        }
    }}
