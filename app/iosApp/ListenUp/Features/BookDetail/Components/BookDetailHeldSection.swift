import SwiftUI

/// Book Detail's triage card for a book held for review (spec §3.2.3 Held; the iPhone Variants board): "Held for
/// Review", one sentence of why ("Hidden from every member, and it can’t be played until you release it."), then the
/// only things a held book allows, as rows of an inset card (HIG, Lists and tables): **Release to Members** in amber
/// (`luWarning`: "waiting for you", never the coral of Play; nothing is deleted, so not destructive red, HIG Buttons),
/// then Edit Details, Match Details… and Edit Chapters. Every row is at least 44 points tall (HIG, Buttons). The
/// toolbar shows the Held pill; the held book's actions are all here.
///
/// It draws what `BookDetailLayout.triageActions` allows, arranged by `arrange(_:)`, so the actions a held book offers
/// are decided in one place and a test on the arrangement pins the rendered rows.
struct BookDetailHeldSection: View {
    typealias Action = BookDetailLayout.TriageAction

    static var message: String { String(localized: "book.detail_held_body") }

    let actions: [Action]
    let isReleasing: Bool
    let perform: (Action) -> Void

    /// The card's rows: Release as the one prominent row, then the rest in `actions`' order.
    struct Arrangement: Equatable {
        let prominent: Action?
        let secondary: [Action]
    }

    nonisolated static func arrange(_ actions: [Action]) -> Arrangement {
        Arrangement(
            prominent: actions.first { $0 == .release },
            secondary: actions.filter { $0 != .release }
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.xs) {
            Text(String(localized: "admin.held_for_review_ios"))
                .font(.headline)
                .padding(.horizontal, Spacing.xxs)
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top, spacing: Spacing.s) {
                    Image(systemName: "tray.full")
                        .foregroundStyle(Color.luWarning)
                        .accessibilityHidden(true)
                    Text(Self.message)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, Spacing.s)
                if let prominent = arrangement.prominent {
                    Divider()
                    row(prominent, isProminent: true)
                }
                ForEach(arrangement.secondary, id: \.self) { action in
                    Divider()
                    row(action, isProminent: false)
                        .disabled(isReleasing)
                }
            }
            .padding(.horizontal, Spacing.m)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
        }
    }

    private var arrangement: Arrangement { Self.arrange(actions) }

    private func row(_ action: Action, isProminent: Bool) -> some View {
        Button { perform(action) } label: {
            HStack(spacing: Spacing.s) {
                Label(Self.title(of: action), systemImage: Self.systemImage(of: action))
                    .fontWeight(isProminent ? .semibold : .regular)
                    .foregroundStyle(isProminent ? AnyShapeStyle(Color.luWarning) : AnyShapeStyle(.tint))
                Spacer(minLength: Spacing.xs)
                if isProminent && isReleasing { ProgressView() }
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isProminent && isReleasing)
    }

    /// The More menu's own words and glyphs, so a held book's actions read as the same actions.
    static func title(of action: Action) -> String {
        switch action {
        case .release: String(localized: "book.detail_held_release_ios")
        case .edit: String(localized: "library.menu_edit_details_ios")
        case .match: String(localized: "book.detail_menu_match_details_ios")
        case .editChapters: String(localized: "book.detail_menu_edit_chapters_ios")
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
