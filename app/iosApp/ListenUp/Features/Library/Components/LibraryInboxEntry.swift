import SwiftUI

/// The Library inbox entry as a value: built only when something is held, so "absent at zero" is a
/// `nil`, not a view that decides to draw nothing.
struct LibraryInboxEntryModel: Equatable {
    let count: Int
    let previewBookIds: [String]

    /// The entry for `count` held books, or nil when nothing is held. The fan shows at most three.
    static func make(count: Int, previewBookIds: [String]) -> LibraryInboxEntryModel? {
        guard count > 0 else { return nil }
        return LibraryInboxEntryModel(count: count, previewBookIds: Array(previewBookIds.prefix(3)))
    }

    /// "Inbox · 3 new books".
    var title: String {
        count == 1
            ? String(format: String(localized: "admin.inbox_entry_title"), count)
            : String(format: String(localized: "admin.inbox_entry_title_plural"), count)
    }

    /// "Inbox, 3 books waiting for review" — the whole row, as one VoiceOver element.
    var accessibilityLabel: String {
        count == 1
            ? String(format: String(localized: "admin.inbox_entry_a11y"), count)
            : String(format: String(localized: "admin.inbox_entry_a11y_plural"), count)
    }
}

/// The Library's way into the admin inbox, as the first row of the Books section: a WarningAmber
/// tile with `tray.full`, the count and "Waiting for you to release", the newest held covers, and a
/// disclosure chevron (HIG, Lists and tables: a chevron means the row navigates). It pushes the
/// inbox onto the Library's own stack, so Back returns here.
struct LibraryInboxEntry: View {
    let model: LibraryInboxEntryModel

    var body: some View {
        NavigationLink(value: AdminInboxDestination()) {
            HStack(spacing: Spacing.s) {
                Image(systemName: "tray.full")
                    .font(.body.weight(.semibold))
                    // White on the light amber, black on the dark one: legible on both.
                    .foregroundStyle(Color(.systemBackground))
                    .frame(width: 36, height: 36)
                    .background(Color.luWarning, in: RoundedRectangle(cornerRadius: Radius.s, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text(model.title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(String(localized: "admin.inbox_entry_subtitle"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                HeldCoverFan(bookIds: model.previewBookIds)
                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, Spacing.s)
            .padding(.vertical, Spacing.xs)
            .frame(minHeight: 64)
            .background(
                Color(.secondarySystemBackground),
                in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.accessibilityLabel)
        .accessibilityAddTraits(.isButton)
    }
}

/// Up to three held covers, overlapped; decoration under the row's single label.
private struct HeldCoverFan: View {
    let bookIds: [String]

    var body: some View {
        HStack(spacing: -13) {
            ForEach(bookIds, id: \.self) { bookId in
                BookCoverImage(bookId: bookId, coverPath: nil, coverHash: nil)
                    .frame(width: 32, height: 32)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.xs, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: Radius.xs, style: .continuous)
                            .stroke(Color(.secondarySystemBackground), lineWidth: 2)
                    )
            }
        }
        .padding(.horizontal, Spacing.xxs)
        .accessibilityHidden(true)
    }
}
