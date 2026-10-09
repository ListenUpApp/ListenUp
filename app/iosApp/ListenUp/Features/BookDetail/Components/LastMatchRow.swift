import SwiftUI

/// Book Detail's last-match row: "Details matched 2 days ago", with See What Changed and Undo Last Match, while the
/// book's last match can still be undone. A grouped card like the held section's; the actions are bordered buttons
/// with full 44-point targets (HIG, Buttons) that sit side by side and stack at large Dynamic Type sizes rather
/// than truncate. A failed Undo says why under the sentence, inline (HIG, Feedback), and the row stays.
struct LastMatchRow: View {
    let model: LastMatchRowModel
    let onSeeWhatChanged: () -> Void
    let onUndo: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            HStack(alignment: .firstTextBaseline, spacing: Spacing.s) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    // Re-said each minute, so "just now" becomes "1 minute ago" while the page is open.
                    TimelineView(.periodic(from: .now, by: 60)) { context in
                        Text(MatchCopy.lastMatch(appliedAt: model.appliedAt, matchedBy: model.matchedBy, now: context.date))
                            .font(.body)
                    }
                    if let error = model.undoError {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Spacing.xs) { actions }
                VStack(spacing: Spacing.xs) { actions }
            }
            .disabled(model.undoing)
        }
        .padding(Spacing.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color(.secondarySystemBackground),
            in: RoundedRectangle(cornerRadius: Radius.xxl, style: .continuous)
        )
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var actions: some View {
        Button(action: onSeeWhatChanged) {
            Text(String(localized: "match.see_what_changed_title"))
                .frame(maxWidth: .infinity, minHeight: TapTarget.minimum)
        }
        .buttonStyle(.bordered)
        Button(action: onUndo) {
            Group {
                if model.undoing {
                    ProgressView()
                        .accessibilityLabel(String(localized: "match.undoing"))
                } else {
                    Text(String(localized: "match.undo_last_match_title"))
                }
            }
            .frame(maxWidth: .infinity, minHeight: TapTarget.minimum)
        }
        .buttonStyle(.bordered)
    }
}
