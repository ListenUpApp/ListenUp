import SwiftUI

/// One ABS-user row in the import Review step: an identity line (avatar + name + email + a
/// matched/review status), then a resolution affordance — the assigned target with a Change
/// button, a one-tap suggestion accept, or Assign / Skip actions.
///
/// Bound to an ``ImportUserRowModel``. The picker is presented by the parent (a confirmation
/// dialog of `listenupUsers`); this row only reports intent via its callbacks.
struct ImportUserReviewRow: View {
    let user: ImportUserRowModel
    let onAcceptSuggestion: () -> Void
    let onAssign: () -> Void
    let onSkip: () -> Void
    let onChange: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            identityRow
            Divider().padding(.leading, 14)
            resolutionRow
        }
        .fieldCard()
    }

    // MARK: - Identity

    private var identityRow: some View {
        HStack(spacing: 13) {
            ImportUserAvatar(initial: user.initial, isMatched: user.resolution != .needsReview)
            VStack(alignment: .leading, spacing: 1) {
                Text(user.username)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                if let email = user.email, !email.isEmpty {
                    Text(email)
                        .font(.footnote)
                        .foregroundStyle(Color.luLabel2)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
            Spacer(minLength: 8)
            statusPill
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    @ViewBuilder
    private var statusPill: some View {
        switch user.resolution {
        case .needsReview:
            Text(String(localized: "import.user_needs_review"))
                .font(.caption.weight(.bold))
                .foregroundStyle(.orange)
                .padding(.horizontal, 11)
                .padding(.vertical, 5)
                .background(Color.orange.opacity(0.16), in: Capsule())
        case .assigned, .skipped:
            Label(
                user.resolution == .skipped
                    ? String(localized: "import.user_skipped")
                    : String(localized: "import.matched_a11y"),
                systemImage: "checkmark"
            )
            .labelStyle(.titleAndIcon)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(user.resolution == .skipped ? Color.luLabel2 : .green)
        }
    }

    // MARK: - Resolution

    @ViewBuilder
    private var resolutionRow: some View {
        switch user.resolution {
        case .assigned(let name):
            assignedRow(name: name)
        case .skipped:
            actionRow
        case .needsReview:
            if let suggestion = user.suggestedName {
                suggestionRow(name: suggestion)
                Divider().padding(.leading, 14)
            }
            actionRow
        }
    }

    private func assignedRow(name: String) -> some View {
        HStack(spacing: 11) {
            Image(systemName: "link")
                .font(.subheadline)
                .foregroundStyle(Color.luLabel2)
            Text(String(format: String(localized: "import.user_assigned_to"), name))
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
                .lineLimit(1)
            Spacer(minLength: 8)
            Button(String(localized: "import.change"), action: onChange)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.luTint)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 13)
    }

    private func suggestionRow(name: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "sparkles")
                .font(.subheadline)
                .foregroundStyle(Color.luTint)
            Text(String(format: String(localized: "import.suggested_name"), name))
                .font(.footnote)
                .foregroundStyle(Color.luLabel2)
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 8)
            Button(action: onAcceptSuggestion) {
                Label(String(localized: "import.accept"), systemImage: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.luOnTint)
                    .padding(.horizontal, 13)
                    .padding(.vertical, 6)
                    .background(Color.luTint, in: Capsule())
            }
            .buttonStyle(.pressScaleChip)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    private var actionRow: some View {
        HStack(spacing: 0) {
            Button(action: onAssign) {
                Label(String(localized: "import.user_assign"), systemImage: "person")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luTint)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.pressScaleChip)
            Divider().frame(height: 24)
            Button(action: onSkip) {
                Label(String(localized: "import.user_skip"), systemImage: "xmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.luLabel2)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.pressScaleChip)
        }
        .padding(.vertical, 13)
    }
}

// MARK: - Avatar

/// A circular initial avatar for an ABS user, tinted green when matched/resolved and coral when
/// it still needs review.
private struct ImportUserAvatar: View {
    let initial: String
    let isMatched: Bool

    var body: some View {
        Circle()
            .fill(
                LinearGradient(
                    colors: isMatched
                        ? [Color(hex: "4FBE7E"), Color(hex: "2E9E5B")]
                        : [Color(hex: "F0894F"), Color(hex: "D8431F")],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )
            )
            .frame(width: 40, height: 40)
            .overlay {
                Text(initial)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
            }
            .accessibilityHidden(true)
    }
}

// MARK: - Preview

#Preview("ImportUserReviewRow") {
    ScrollView {
        VStack(spacing: 18) {
            ImportUserReviewRow(
                user: ImportUserRowModel(
                    absUserId: "s", username: "simon", email: "simon@example.com",
                    suggestedUserId: "u1", suggestedName: "Simon Hull", resolution: .needsReview
                ),
                onAcceptSuggestion: {}, onAssign: {}, onSkip: {}, onChange: {}
            )
            ImportUserReviewRow(
                user: ImportUserRowModel(
                    absUserId: "d", username: "darlene", email: "darlene@example.com",
                    suggestedUserId: nil, suggestedName: nil, resolution: .assigned(toName: "Darlene Hull")
                ),
                onAcceptSuggestion: {}, onAssign: {}, onSkip: {}, onChange: {}
            )
            ImportUserReviewRow(
                user: ImportUserRowModel(
                    absUserId: "r", username: "root", email: nil,
                    suggestedUserId: nil, suggestedName: nil, resolution: .needsReview
                ),
                onAcceptSuggestion: {}, onAssign: {}, onSkip: {}, onChange: {}
            )
        }
        .padding()
    }
    .background(Color.luSurface)
}
