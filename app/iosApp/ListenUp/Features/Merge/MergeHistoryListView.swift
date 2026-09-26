import SwiftUI

/// The "Merged into this" list (#1061): every merge folded into this series or genre that can
/// still be undone, each with Undo. Used by the series editor and the genre admin's history sheet;
/// the host supplies the card or sheet around it.
///
/// Undo is confirmed first — it brings the merged-away series or genre back and moves books — and
/// what came back is said in words, where the reader is already looking.
struct MergeHistoryListView: View {
    let model: MergeHistoryModel
    let onUndo: (String) -> Void
    let onRetry: () -> Void

    @State private var confirming: MergeRow?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            switch model {
            case .loading:
                ProgressView().frame(maxWidth: .infinity)

            case .unavailable(let message):
                Text(message).font(.subheadline)
                Button(String(localized: "merge_history.retry"), action: onRetry)

            case .ready(let rows, let undoingId, let outcome):
                if let outcome {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(MergeHistoryText.outcome(outcome), id: \.self) { line in
                            Text(line).font(.subheadline)
                        }
                    }
                    .foregroundStyle(Color.listenUpOrange)
                    .accessibilityElement(children: .combine)
                }
                if rows.isEmpty {
                    Text(String(localized: "merge_history.empty"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(rows) { row in
                    rowView(row, isUndoing: row.id == undoingId, canUndo: undoingId == nil)
                }
                Text(String(localized: "merge_history.older_note"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .confirmationDialog(
            String(localized: "merge_history.confirm_title"),
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
            titleVisibility: .visible,
            presenting: confirming
        ) { row in
            Button(String(localized: "merge_history.confirm_action")) { onUndo(row.id) }
            Button(String(localized: "common.cancel"), role: .cancel) {}
        } message: { row in
            Text(String(format: String(localized: "merge_history.confirm_body"), row.sourceName))
        }
    }

    private func rowView(_ row: MergeRow, isUndoing: Bool, canUndo: Bool) -> some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.sourceName).font(.body.weight(.semibold)).lineLimit(1)
                Text(MergeHistoryText.detail(row)).font(.caption).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            if isUndoing {
                HStack(spacing: 6) {
                    ProgressView()
                    Text(String(localized: "merge_history.undoing")).font(.subheadline)
                }
            } else {
                // One undo at a time: the others wait rather than queue behind it.
                Button(String(localized: "merge_history.undo")) { confirming = row }
                    .buttonStyle(.bordered)
                    .disabled(!canUndo)
            }
        }
    }
}
