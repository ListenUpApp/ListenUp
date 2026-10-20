import SwiftUI

/// The Library inbox entry as a value: built only when something is held, so "absent at zero" is a
/// `nil`, not a view that decides to draw nothing.
struct LibraryInboxEntryModel: Equatable {
    let count: Int

    /// The entry for `count` held books, or nil when nothing is held.
    static func make(count: Int) -> LibraryInboxEntryModel? {
        guard count > 0 else { return nil }
        return LibraryInboxEntryModel(count: count)
    }

    /// "Inbox · 3 waiting" (spec a6).
    var title: String {
        String(format: String(localized: "library.inbox_strip"), count)
    }

    /// "Inbox, 3 books waiting for review" — the whole row, as one VoiceOver element.
    var accessibilityLabel: String {
        count == 1
            ? String(format: String(localized: "admin.inbox_entry_a11y"), count)
            : String(format: String(localized: "admin.inbox_entry_a11y_plural"), count)
    }
}

/// The Library's way into the admin inbox, as the first row of the Books section (spec a6): one line, a
/// WarningAmber tile with `tray.full`, "Inbox · 3 waiting", and a disclosure chevron (HIG, Lists and
/// tables: a chevron means the row navigates). At least 44pt tall (HIG: 44 × 44 pt targets). It pushes
/// the inbox onto the Library's own stack, so Back returns here.
struct LibraryInboxEntry: View {
    let model: LibraryInboxEntryModel
    /// The tile grows with Dynamic Type, as the glyph inside it does.
    @ScaledMetric(relativeTo: .body) private var tileSize: CGFloat = 24

    var body: some View {
        NavigationLink(value: AdminInboxDestination()) {
            HStack(spacing: Spacing.s) {
                Image(systemName: "tray.full")
                    .font(.footnote.weight(.semibold))
                    // White on the light amber, black on the dark one: legible on both.
                    .foregroundStyle(Color(.systemBackground))
                    .frame(width: tileSize, height: tileSize)
                    .background(Color.luWarning, in: RoundedRectangle(cornerRadius: Radius.xs, style: .continuous))
                Text(model.title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xxs)
            .frame(minHeight: 44)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // A NavigationLink is already one element with the button trait; this only names it.
        .accessibilityLabel(model.accessibilityLabel)
    }
}
