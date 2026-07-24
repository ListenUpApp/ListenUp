import SwiftUI

// MARK: - Review

/// The Review-users step: a count summary header, a stacked list of ``ImportUserReviewRow``s, an
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

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    countHeader
                    ForEach(review.users) { user in
                        ImportUserReviewRow(
                            user: user,
                            onAcceptSuggestion: {
                                if let suggested = user.suggestedUserId { onAccept(user, suggested) }
                            },
                            onAssign: { onAssign(user) },
                            onSkip: { onSkip(user) },
                            onChange: { onAssign(user) }
                        )
                        .popover(item: assignPopoverBinding(for: user)) { _ in
                            assignPicker(for: user)
                        }
                    }

                    booksSection

                    if review.unresolvedCount > 0 {
                        warning
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
                .readableWidth(640)
            }
            actionTray
        }
    }

    // MARK: - Books section

    @ViewBuilder
    private var booksSection: some View {
        booksHeader
            .padding(.top, 6)

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

        if review.books.isEmpty {
            summaryLine(String(localized: "import.no_books_to_review"))
        } else {
            ForEach(review.books) { book in
                ImportBookReviewRow(
                    book: book,
                    search: activeSearch(for: book),
                    onOpenSearch: { onOpenBookSearch(book.absItemId) },
                    onCloseSearch: onCloseBookSearch,
                    onQueryChange: onBookSearchQueryChange,
                    onSelectBook: { bookId in onSelectBook(book.absItemId, bookId) },
                    onSkip: { onSkipBook(book.absItemId) }
                )
            }
        }
    }

    /// The open search panel iff it belongs to this book row (only one panel open at a time).
    private func activeSearch(for book: ImportBookRowModel) -> ImportBookSearchModel? {
        guard let search = review.bookSearch, search.absItemId == book.absItemId else { return nil }
        return search
    }

    private var booksHeader: some View {
        HStack(spacing: 12) {
            Text(String(localized: "import.review_books_section").uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .tracking(0.4)
            Spacer(minLength: 8)
            if review.unresolvedBookCount > 0 {
                Label(
                    String(format: String(localized: "import.n_to_review"), review.unresolvedBookCount),
                    systemImage: "circle.fill"
                )
                .labelStyle(.titleAndIcon)
                .imageScale(.small)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.orange)
            }
        }
        .padding(.horizontal, 6)
    }

    private func summaryLine(_ text: String) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(Color.luLabel2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 6)
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
            Text(String(localized: "import.users_in_backup").uppercased())
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.luLabel2)
                .tracking(0.4)
            Spacer(minLength: 8)
            if review.unresolvedCount > 0 {
                Label(
                    String(format: String(localized: "import.n_to_review"), review.unresolvedCount),
                    systemImage: "circle.fill"
                )
                .labelStyle(.titleAndIcon)
                .imageScale(.small)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.orange)
            }
            if review.matchedCount > 0 {
                Label(
                    String(format: String(localized: "import.n_matched"), review.matchedCount),
                    systemImage: "checkmark"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(.green)
            }
        }
        .padding(.horizontal, 6)
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
        .foregroundStyle(.orange)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Color.orange.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }

    private var actionTray: some View {
        PrimaryButton(title: String(localized: "import.apply_import"), icon: "arrow.right", action: onApply)
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .background(.bar)
    }
}

// MARK: - Complete

/// The completion screen: a success badge, a headline, and a grouped stat card. "Done" dismisses
/// the wizard (the parent refreshes the hub and the listening history is already syncing).
struct ImportCompleteContent: View {
    let done: ImportDoneModel
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 0) {
                    SuccessBadge(size: 116)
                        .padding(.top, 16)
                    Text(String(localized: "import.done_title"))
                        .font(.title.weight(.bold))
                        .foregroundStyle(.primary)
                        .padding(.top, 22)
                    Text(String(localized: "import.done_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(Color.luLabel2)
                        .multilineTextAlignment(.center)
                        .padding(.top, 8)
                    statsCard
                        .padding(.top, 24)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 24)
                .frame(maxWidth: .infinity)
                .readableWidth(520)
            }
            PrimaryButton(title: String(localized: "common.done"), icon: "checkmark", action: onDone)
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 16)
                .background(.bar)
        }
    }

    private var statsCard: some View {
        VStack(spacing: 0) {
            StatLineRow(
                systemImage: "doc",
                label: String(localized: "import.records_imported_stat"),
                value: "\(done.importedCount)"
            )
            separator
            StatLineRow(
                systemImage: "waveform",
                label: String(localized: "import.sessions_imported_stat"),
                value: "\(done.sessionsImported)"
            )
            separator
            StatLineRow(
                systemImage: "person.2",
                label: String(localized: "import.users_merged_stat"),
                value: "\(done.usersUpdated)"
            )
            separator
            StatLineRow(
                systemImage: "xmark",
                label: String(localized: "import.books_skipped_stat"),
                value: "\(done.booksNotInLibrary)",
                isMuted: true
            )
        }
        .fieldCard()
    }

    private var separator: some View {
        Rectangle().fill(Color.luSeparator).frame(height: 0.5).padding(.leading, 57)
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
                PrimaryButton(title: String(localized: "common.try_again"), icon: "arrow.clockwise", action: onRetry)
                Button(String(localized: "common.cancel"), action: onCancel)
                    .font(.body.weight(.medium))
                    .foregroundStyle(Color.luLabel2)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
            .readableWidth(520)
        }
    }
}
