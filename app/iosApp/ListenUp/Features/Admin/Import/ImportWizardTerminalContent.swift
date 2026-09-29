import SwiftUI

// MARK: - Review

/// The Review-users step: a count summary header, a grouped list of ``ImportUserReviewRow``s, an
/// optional unresolved warning, and the "Apply Import" action. The admin assigns or skips each
/// ABS user; unresolved users are skipped server-side, so Apply is always enabled (honest: the
/// warning tells them what will be skipped rather than blocking them).
struct ImportReviewContent: View {
    let review: ImportReviewModel
    /// The ABS user whose target picker is open, if any. Bound to the parent so a single row's
    /// popover is presented; the popover itself is attached to that row (below) so it anchors to
    /// the tapped row rather than floating at the top of the screen.
    @Binding var assigningUser: ImportUserRowModel?
    let onAccept: (ImportUserRowModel, String) -> Void
    let onAssign: (ImportUserRowModel) -> Void
    let onSkip: (ImportUserRowModel) -> Void
    let onOpenBookSearch: (String) -> Void
    let onCloseBookSearch: () -> Void
    let onBookSearchQueryChange: (String) -> Void
    let onSelectBook: (String, String) -> Void
    let onSkipBook: (String) -> Void
    let onApply: () -> Void

    /// A grouped `List`: each ABS user and each book to review is its own section (a compound row),
    /// with the counts in real section headers. HIG, Lists and tables.
    var body: some View {
        List {
            ForEach(Array(review.users.enumerated()), id: \.element.id) { index, user in
                Section {
                    ImportUserReviewRow(
                        user: user,
                        onAcceptSuggestion: {
                            if let suggested = user.suggestedUserId { onAccept(user, suggested) }
                        },
                        onAssign: { onAssign(user) },
                        onSkip: { onSkip(user) },
                        onChange: { onAssign(user) }
                    )
                    .listRowInsets(EdgeInsets())
                    .popover(item: assignPopoverBinding(for: user)) { _ in
                        assignPicker(for: user)
                    }
                } header: {
                    if index == 0 { countHeader }
                }
            }

            booksSections

            if review.unresolvedCount > 0 {
                Section {
                    warning
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)
                }
            }
        }
        .listStyle(.insetGrouped)
        .listSectionSpacing(.compact)
        .readableListWidth()
        // The tray is a bar over the scroll view, whose edge effect the system draws (HIG, Toolbars).
        .safeAreaBar(edge: .bottom) {
            actionTray
        }
    }

    // MARK: - Books section

    @ViewBuilder
    private var booksSections: some View {
        if review.books.isEmpty {
            Section {
                Text(String(localized: "import.no_books_to_review"))
                    .foregroundStyle(.secondary)
            } header: {
                booksHeader
            }
        } else {
            ForEach(Array(review.books.enumerated()), id: \.element.id) { index, book in
                Section {
                    ImportBookReviewRow(
                        book: book,
                        search: activeSearch(for: book),
                        onOpenSearch: { onOpenBookSearch(book.absItemId) },
                        onCloseSearch: onCloseBookSearch,
                        onQueryChange: onBookSearchQueryChange,
                        onSelectBook: { bookId in onSelectBook(book.absItemId, bookId) },
                        onSkip: { onSkipBook(book.absItemId) }
                    )
                    .listRowInsets(EdgeInsets())
                } header: {
                    if index == 0 { booksHeader }
                }
            }
        }
    }

    /// The open search panel iff it belongs to this book row (only one panel open at a time).
    private func activeSearch(for book: ImportBookRowModel) -> ImportBookSearchModel? {
        guard let search = review.bookSearch, search.absItemId == book.absItemId else { return nil }
        return search
    }

    private var booksHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Text(String(localized: "import.review_books_section"))
                Spacer(minLength: 8)
                if review.unresolvedBookCount > 0 {
                    Label(
                        String(format: String(localized: "import.n_to_review"), review.unresolvedBookCount),
                        systemImage: "circle.fill"
                    )
                    .labelStyle(.titleAndIcon)
                    .imageScale(.small)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.luWarning)
                    .textCase(nil)
                }
            }
            if review.autoMatchedCount > 0 {
                summaryLine(
                    String(
                        format: String(localized: "import.books_matched"),
                        String(review.autoMatchedCount)
                    )
                )
            }
            if review.importableSessionCount > 0 {
                summaryLine(
                    String(
                        format: String(localized: "import.sessions_importable"),
                        String(review.importableSessionCount)
                    )
                )
            }
        }
    }

    private func summaryLine(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .textCase(nil)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// A binding that is non-nil only for the row whose picker is open, so the `.popover(item:)`
    /// attached to each row presents on exactly that row (and thus anchors to it).
    private func assignPopoverBinding(for user: ImportUserRowModel) -> Binding<ImportUserRowModel?> {
        Binding(
            get: { assigningUser?.absUserId == user.absUserId ? assigningUser : nil },
            set: { assigningUser = $0 }
        )
    }

    /// The target-user picker shown in the row popover. On a regular width it renders as a popover
    /// anchored to the row; on a compact width SwiftUI adapts it to a sheet automatically.
    private func assignPicker(for user: ImportUserRowModel) -> some View {
        List(review.listenupUsers) { pickerUser in
            Button {
                onAccept(user, pickerUser.id)
                assigningUser = nil
            } label: {
                Text(pickerUser.name)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .listStyle(.plain)
        .frame(minWidth: 260, idealWidth: 300, minHeight: 240, idealHeight: 320)
        .presentationDetents([.medium, .large])
    }

    private var countHeader: some View {
        HStack(spacing: 12) {
            Text(String(localized: "import.users_in_backup"))
            Spacer(minLength: 8)
            if review.unresolvedCount > 0 {
                Label(
                    String(format: String(localized: "import.n_to_review"), review.unresolvedCount),
                    systemImage: "circle.fill"
                )
                .labelStyle(.titleAndIcon)
                .imageScale(.small)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.luWarning)
                .textCase(nil)
            }
            if review.matchedCount > 0 {
                Label(
                    String(format: String(localized: "import.n_matched"), review.matchedCount),
                    systemImage: "checkmark"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.green)
                .textCase(nil)
            }
        }
    }

    private var warning: some View {
        Label(
            // The localized format uses a `%@` slot (generated from `%s`); it must be fed a String,
            // NOT a raw Int. Passing an Int makes `String(format:)` treat it as a pointer — Foundation
            // logs a format mismatch and the warning fails to render, stalling the import terminal.
            String(format: String(localized: "import.review_users_unresolved_warning"),
                   String(review.unresolvedCount)),
            systemImage: "exclamationmark.triangle"
        )
        .font(.footnote)
        .foregroundStyle(Color.luWarning)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.s)
        .background(Color.luWarning.opacity(0.1), in: RoundedRectangle(cornerRadius: Radius.m))
    }

    private var actionTray: some View {
        Button(action: onApply) {
            ActionLabel(title: String(localized: "import.apply_import"), systemImage: "arrow.right")
        }
        .prominentAction()
        .padding(.horizontal, Spacing.l)
        .padding(.top, Spacing.s)
        .padding(.bottom, Spacing.m)
    }
}

// MARK: - Complete

/// The completion screen: a success badge, a headline, and a grouped section of figures. "Done" dismisses
/// the wizard (the parent refreshes the hub and the listening history is already syncing).
struct ImportCompleteContent: View {
    let done: ImportDoneModel
    let onDone: () -> Void

    /// A grouped `List`: the success hero heads it on the plain background, and the figures are a
    /// section of system rows beneath. HIG, Lists and tables.
    var body: some View {
        List {
            Section {
                VStack(spacing: 0) {
                    SuccessBadge(size: 116)
                        .padding(.top, Spacing.m)
                    Text(String(localized: "import.done_title"))
                        .font(.title.weight(.bold))
                        .foregroundStyle(.primary)
                        .padding(.top, Spacing.xl)
                    Text(String(localized: "import.done_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.top, Spacing.xs)
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                StatLineRow(
                    systemImage: "doc",
                    label: String(localized: "import.records_imported_stat"),
                    value: "\(done.importedCount)"
                )
                StatLineRow(
                    systemImage: "waveform",
                    label: String(localized: "import.sessions_imported_stat"),
                    value: "\(done.sessionsImported)"
                )
                StatLineRow(
                    systemImage: "person.2",
                    label: String(localized: "import.users_merged_stat"),
                    value: "\(done.usersUpdated)"
                )
                StatLineRow(
                    systemImage: "xmark",
                    label: String(localized: "import.books_skipped_stat"),
                    value: "\(done.booksNotInLibrary)",
                    isMuted: true
                )
            }
        }
        .listStyle(.insetGrouped)
        .readableListWidth(520)
        // The tray is a bar over the scroll view, whose edge effect the system draws (HIG, Toolbars).
        .safeAreaBar(edge: .bottom) {
            Button(action: onDone) {
                ActionLabel(title: String(localized: "common.done"), systemImage: "checkmark")
            }
            .prominentAction()
            .padding(.horizontal, Spacing.l)
            .padding(.top, Spacing.s)
            .padding(.bottom, Spacing.m)
        }
    }
}

// MARK: - Error

/// A failed-phase screen: the localized error message with a Try Again (resets the wizard to its
/// idle intro) and a Cancel that dismisses the sheet.
struct ImportErrorContent: View {
    let message: String
    let onRetry: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer()
            ContentUnavailableView {
                Label(String(localized: "import.error_title"), systemImage: "exclamationmark.triangle")
            } description: {
                Text(message)
            }
            Spacer()
            VStack(spacing: 10) {
                Button(action: onRetry) {
                    ActionLabel(title: String(localized: "common.try_again"), systemImage: "arrow.clockwise")
                }
                .prominentAction()
                Button(String(localized: "common.cancel"), action: onCancel)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, Spacing.l)
            .padding(.bottom, Spacing.m)
            .readableWidth(520)
        }
    }
}
