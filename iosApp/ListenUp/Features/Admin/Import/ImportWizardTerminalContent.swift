import SwiftUI

// MARK: - Review

/// The Review-users step: a count summary header, a stacked list of ``ImportUserReviewRow``s, an
/// optional unresolved warning, and the "Apply Import" action. The admin assigns or skips each
/// ABS user; unresolved users are skipped server-side, so Apply is always enabled (honest: the
/// warning tells them what will be skipped rather than blocking them).
struct ImportReviewContent: View {
    let review: ImportReviewModel
    let onAccept: (ImportUserRowModel, String) -> Void
    let onAssign: (ImportUserRowModel) -> Void
    let onSkip: (ImportUserRowModel) -> Void
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
                    }
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
            String(format: String(localized: "import.review_users_unresolved_warning"), review.unresolvedCount),
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
                value: "\(done.skippedCount)",
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
