import SwiftUI

/// Book Detail's triage section for a book held for review: a grouped-inset card headed "Held for
/// review", saying it is hidden from every member and can't be played until released, with the
/// screen's one prominent button, Release — in the tint, not destructive red, because nothing is
/// deleted (HIG, Buttons) — and, under it, the two secondary metadata fixes (spec §10), bordered:
/// Match and Edit chapters, in the overflow menu's own words and glyphs. Edit lives in the toolbar
/// (HIG, Toolbars).
struct BookDetailHeldSection: View {
    let isReleasing: Bool
    let onRelease: () -> Void
    let onMatch: () -> Void
    let onEditChapters: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "admin.held_for_review"))
                .font(.headline)
                .padding(.horizontal, Spacing.xxs)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: Spacing.s) {
                HStack(alignment: .top, spacing: Spacing.s) {
                    Image(systemName: "tray.full")
                        .foregroundStyle(Color.luWarning)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(String(localized: "admin.held_hidden_from_members"))
                            .font(.body)
                        Text(String(localized: "admin.held_cannot_play"))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                Button(action: onRelease) {
                    ActionLabel(title: String(localized: "admin.release"), isBusy: isReleasing)
                }
                .prominentAction()
                .disabled(isReleasing)
                // Secondary (spec §10). ViewThatFits keeps them side by side, stacking at large
                // Dynamic Type sizes rather than truncating.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: Spacing.xs) { secondaryActions }
                    VStack(spacing: Spacing.xs) { secondaryActions }
                }
                .disabled(isReleasing)
            }
            .padding(Spacing.m)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
        }
    }

    @ViewBuilder
    private var secondaryActions: some View {
        Button(action: onMatch) {
            Label(String(localized: "metadata.match_on_audible"), systemImage: "sparkles")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        Button(action: onEditChapters) {
            Label(String(localized: "chapter_editor.title"), systemImage: "list.bullet.indent")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
    }
}
