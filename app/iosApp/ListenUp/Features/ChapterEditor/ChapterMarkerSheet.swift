import Shared
import SwiftUI

/// Naming a marker and placing it, in the standard edit sheet.
///
/// A new marker is only written on Save, so Cancel means what it says: the book is left as it was.
/// Moving is a push inside the sheet rather than a second sheet, and it too waits for Save.
struct ChapterMarkerSheet: View {
    let edit: MarkerEdit
    let chapters: [EditableChapterRow]
    let onSave: (_ name: String, _ startsAtChapterId: String) -> Void
    let onRemove: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String
    @State private var startsAt: String

    init(
        edit: MarkerEdit,
        chapters: [EditableChapterRow],
        onSave: @escaping (_ name: String, _ startsAtChapterId: String) -> Void,
        onRemove: @escaping () -> Void
    ) {
        self.edit = edit
        self.chapters = chapters
        self.onSave = onSave
        self.onRemove = onRemove
        _name = State(initialValue: edit.name)
        _startsAt = State(initialValue: edit.chapterId)
    }

    private var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var hasChanges: Bool {
        edit.isNew || trimmedName != edit.name || startsAt != edit.chapterId
    }

    var body: some View {
        EditSheetScaffold(
            title: title,
            hasChanges: hasChanges && !edit.isNew,
            canSave: MarkerEdit.accepts(trimmedName) && hasChanges,
            isSaving: false,
            saveLabel: String(localized: "common.save"),
            onCancel: { dismiss() },
            onSave: {
                onSave(trimmedName, startsAt)
                dismiss()
            },
            content: {
                Section {
                    AppTextField(
                        placeholder: String(localized: "chapter_editor.marker_name"),
                        text: $name,
                        entry: .words,
                        label: String(localized: "chapter_editor.marker_name"),
                        submitLabel: .done
                    )
                    // Capped as it is typed, as Android's field is. `canSave` still decides, because
                    // a character can be longer than one of the units the wire counts.
                    .onChange(of: name) { _, typed in
                        if typed.count > MarkerEdit.maxNameLength {
                            name = String(typed.prefix(MarkerEdit.maxNameLength))
                        }
                    }
                    startsAtRow
                }
                if !edit.isNew {
                    Section {
                        Button(String(localized: "chapter_editor.marker_remove_ios"), role: .destructive) {
                            onRemove()
                            dismiss()
                        }
                    }
                }
            }
        )
    }

    private var title: String {
        switch (edit.tier, edit.isNew) {
        case (.part, true): String(localized: "chapter_editor.marker_new_part_ios")
        case (.book, true): String(localized: "chapter_editor.marker_new_book_ios")
        case (.part, false): String(localized: "chapter_editor.marker_edit_part_ios")
        case (.book, false): String(localized: "chapter_editor.marker_edit_book_ios")
        }
    }

    /// ⛔ A plain row for a new marker, not a disabled link: it starts where it was asked to, and a
    /// chevron would promise a choice this sheet does not offer until the marker exists.
    @ViewBuilder
    private var startsAtRow: some View {
        if edit.isNew {
            startsAtLabel
        } else {
            NavigationLink {
                ChapterMarkerStartPicker(chapters: chapters, tier: edit.tier) { startsAt = $0 }
            } label: {
                startsAtLabel
            }
        }
    }

    private var startsAtLabel: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(String(localized: "chapter_editor.marker_starts_at_ios"))
            if let chapter = chapters.first(where: { $0.id == startsAt }) {
                Text(String(
                    format: String(localized: "chapter_editor.marker_starts_at_value"),
                    chapter.number,
                    chapter.title,
                    ChapterTimeFormat.shared.clock(ms: chapter.startMs)
                ))
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
        }
    }
}

/// The chapters a marker can move to. One already opening that tier is not among them.
private struct ChapterMarkerStartPicker: View {
    let chapters: [EditableChapterRow]
    let tier: MarkerTier
    let onPick: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        List(chapters.filter { !$0.opens(tier) }) { chapter in
            Button {
                onPick(chapter.id)
                dismiss()
            } label: {
                HStack(spacing: 12) {
                    Text("\(chapter.number)")
                        .font(.caption.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(minWidth: 32, alignment: .trailing)
                    Text(chapter.title)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Text(ChapterTimeFormat.shared.clock(ms: chapter.startMs))
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            .foregroundStyle(.primary)
        }
        .navigationTitle(String(localized: "chapter_editor.marker_move_title_ios"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
