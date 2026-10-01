import SwiftUI

/// Book Detail's triage section for a book held for review: a grouped-inset card headed "Held for
/// review", saying it is hidden from every member and can't be played until released, with the
/// screen's one prominent button, Release — in the tint, not destructive red, because nothing is
/// deleted (HIG, Buttons) — and, under it, the two secondary metadata fixes (spec §10), bordered:
/// Match and Edit chapters, in the overflow menu's own words and glyphs. Edit lives in the toolbar
/// (HIG, Toolbars).
///
/// It draws what `BookDetailLayout.triageActions` allows, arranged by `arrange(_:)`, so the actions a
/// held book offers are decided in one place and a test on the arrangement pins the rendered buttons.
struct BookDetailHeldSection: View {
    typealias Action = BookDetailLayout.TriageAction

    let actions: [Action]
    let isReleasing: Bool
    let perform: (Action) -> Void

    /// The section's buttons: Release as the one prominent button, and the rest — but Edit, which is
    /// the toolbar's — as secondary ones, in `actions`' order.
    struct Arrangement: Equatable {
        let prominent: Action?
        let secondary: [Action]
    }

    nonisolated static func arrange(_ actions: [Action]) -> Arrangement {
        Arrangement(
            prominent: actions.first { $0 == .release },
            secondary: actions.filter { $0 != .release && $0 != .edit }
        )
    }

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
                if let prominent = arrangement.prominent {
                    Button { perform(prominent) } label: {
                        ActionLabel(title: Self.title(of: prominent), isBusy: isReleasing)
                    }
                    .prominentAction()
                    .disabled(isReleasing)
                }
                // Secondary (spec §10). ViewThatFits keeps them side by side, stacking at large
                // Dynamic Type sizes rather than truncating.
                if !arrangement.secondary.isEmpty {
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: Spacing.xs) { secondaryActions }
                        VStack(spacing: Spacing.xs) { secondaryActions }
                    }
                    .disabled(isReleasing)
                }
            }
            .padding(Spacing.m)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
        }
    }

    private var arrangement: Arrangement { Self.arrange(actions) }

    /// Bordered and large: a secondary button still gets the 44-point target (HIG, Buttons).
    private var secondaryActions: some View {
        ForEach(arrangement.secondary, id: \.self) { action in
            Button { perform(action) } label: {
                Label(Self.title(of: action), systemImage: Self.systemImage(of: action))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
    }

    /// The overflow menu's own words and glyphs, so a held book's actions read as the same actions.
    private static func title(of action: Action) -> String {
        switch action {
        case .release: String(localized: "admin.release")
        case .edit: String(localized: "common.edit")
        case .match: String(localized: "metadata.match_on_audible")
        case .editChapters: String(localized: "chapter_editor.title")
        }
    }

    private static func systemImage(of action: Action) -> String {
        switch action {
        case .release: "checkmark"
        case .edit: "pencil"
        case .match: "sparkles"
        case .editChapters: "list.bullet.indent"
        }
    }
}
