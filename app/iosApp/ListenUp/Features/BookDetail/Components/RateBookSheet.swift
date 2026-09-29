import SwiftUI
import Shared

/// The sheet for rating a book: input stars, an optional note with an "n/280" counter, Save, and —
/// when you have already rated the book — Remove rating. It opens on your current rating, or on no
/// stars; Save stays disabled until at least one star is chosen. Save and Remove both close it.
struct RateBookSheet: View {
    let current: MyRating?
    let onSave: (_ halfStars: Int, _ note: String) -> Void
    let onClear: () -> Void
    let onClose: () -> Void

    @State private var halfStars: Int
    @State private var note: String

    init(
        current: MyRating?,
        onSave: @escaping (_ halfStars: Int, _ note: String) -> Void,
        onClear: @escaping () -> Void,
        onClose: @escaping () -> Void
    ) {
        self.current = current
        self.onSave = onSave
        self.onClear = onClear
        self.onClose = onClose
        _halfStars = State(initialValue: current?.halfStars ?? 0)
        _note = State(initialValue: current?.note ?? "")
    }

    /// Whether the sheet holds a rating other than the one it opened on — what Cancel would lose.
    nonisolated static func hasChanges(halfStars: Int, note: String, openedOn current: MyRating?) -> Bool {
        halfStars != (current?.halfStars ?? 0) || note != (current?.note ?? "")
    }

    var body: some View {
        EditSheetScaffold(
            title: String(localized: "book.detail_rating_sheet_title"),
            hasChanges: Self.hasChanges(halfStars: halfStars, note: note, openedOn: current),
            canSave: halfStars >= RatingStarsView.minHalfStars,
            isSaving: false,
            saveLabel: String(localized: "book.detail_rating_save"),
            onCancel: onClose,
            onSave: {
                onSave(halfStars, note)
                onClose()
            }
        ) {
            VStack(spacing: 20) {
                RatingStarsView(halfStars: halfStars) { halfStars = $0 }

                noteField

                if current != nil {
                    Button(role: .destructive) {
                        onClear()
                        onClose()
                    } label: {
                        Text(String(localized: "book.detail_rating_clear"))
                            .font(.body.weight(.medium))
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.borderless)
                }
            }
            .padding(.horizontal)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var noteField: some View {
        VStack(alignment: .trailing, spacing: 6) {
            AppTextField(
                placeholder: String(localized: "book.detail_rating_note_hint"),
                text: $note,
                entry: .sentences,
                axis: .vertical
            )
            .fieldCard()
            // Re-assigning a different value is what makes the field redraw; a binding setter that
            // quietly kept the old value would leave the refused text on screen.
            .onChange(of: note) { previous, proposed in
                let limited = RatingNote.limited(proposed, previous: previous, maxLength: RatingNote.maxLength)
                if limited != proposed { note = limited }
            }

            Text(String(
                format: String(localized: "book.detail_rating_note_counter"),
                RatingNote.length(note),
                RatingNote.maxLength
            ))
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Note cap

/// The note's length cap, counted the way the server counts it.
///
/// Kotlin's `String.length` counts UTF-16 units, and the contract caps the note at that many.
/// Swift's `count` counts grapheme clusters, so an emoji (one Character, two units) would slip
/// past a `count` check and be rejected by the server. An edit that would cross the cap keeps only
/// as much of the *inserted* text as fits — whole characters, never half a surrogate pair — and
/// never chops the end of what was already written.
enum RatingNote {
    nonisolated static var maxLength: Int { Int(ListenerRatingLimits.shared.NOTE_MAX_CHARS) }

    /// The note's length in UTF-16 units — the unit the server validates.
    nonisolated static func length(_ note: String) -> Int { note.utf16.count }

    /// `proposed` if it fits in `maxLength` (or shortens `previous`); otherwise `proposed` with its
    /// inserted run clipped to what fits between the unchanged head and tail.
    nonisolated static func limited(_ proposed: String, previous: String, maxLength: Int) -> String {
        let proposedLength = length(proposed)
        guard proposedLength > maxLength, proposedLength > length(previous) else { return proposed }

        let old = Array(previous)
        let new = Array(proposed)
        var head = 0
        while head < old.count, head < new.count, old[head] == new[head] { head += 1 }
        var tail = 0
        while tail < old.count - head, tail < new.count - head,
              old[old.count - 1 - tail] == new[new.count - 1 - tail] {
            tail += 1
        }

        let kept = String(new[..<head])
        let ending = String(new[(new.count - tail)...])
        var budget = maxLength - length(kept) - length(ending)
        var inserted = ""
        for character in new[head..<(new.count - tail)] {
            let units = String(character).utf16.count
            guard units <= budget else { break }
            inserted.append(character)
            budget -= units
        }
        return kept + inserted + ending
    }
}
