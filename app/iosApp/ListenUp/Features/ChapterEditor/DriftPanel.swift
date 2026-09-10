import SwiftUI

/// The guided drift flow.
///
/// Drift is the editor's answer to *bulk* error — a scrape whose offset grows across the book,
/// where fixing 311 boundaries by hand is not a real option. The reader pins two chapters they can
/// hear are right; everything between is interpolated.
///
/// ⛔ Nothing moves until Apply. This is a proposal: the summary describes what *would* happen, and
/// abandoning the flow leaves the chapter set exactly as it was.
///
/// A refusal is shown as a sentence, not a greyed-out button. A mis-set anchor is the likeliest
/// mistake here, and "Apply is disabled" tells the reader nothing about which of the two is wrong.
struct DriftPanel: View {
    let drift: DriftModel
    let canPin: Bool
    let needsSelection: Bool
    let onPin: () -> Void
    let onApply: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(String(localized: "chapter_editor.drift_title"))
                .font(.headline)
            Text(String(localized: "chapter_editor.drift_intro"))
                .font(.subheadline)
                .foregroundStyle(.secondary)

            anchor(String(localized: "chapter_editor.drift_anchor_first"), value: drift.firstAnchor)
            anchor(String(localized: "chapter_editor.drift_anchor_second"), value: drift.secondAnchor)

            // The two things a pin needs, named separately: which boundary, and where it really is.
            // One message covering both would leave the reader guessing which half is missing.
            if canPin {
                Button(
                    drift.firstAnchor == nil
                        ? String(localized: "chapter_editor.drift_pin_first")
                        : String(localized: "chapter_editor.drift_pin_second"),
                    action: onPin
                )
                .buttonStyle(.bordered)
            } else if needsSelection {
                Text(String(localized: "chapter_editor.drift_needs_selection"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text(String(localized: "chapter_editor.drift_needs_playhead"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            if let summary = drift.summary {
                Text(summary)
                    .font(.subheadline.weight(.semibold))
            }
            if let refusal = drift.refusal {
                Text(refusal)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.listenUpOrange)
            }

            HStack {
                Button(String(localized: "common.cancel"), action: onCancel)
                    .buttonStyle(.bordered)
                Spacer()
                Button(String(localized: "chapter_editor.drift_apply"), action: onApply)
                    .buttonStyle(.borderedProminent)
                    .disabled(!drift.canApply)
            }
        }
        .padding(.vertical, 6)
    }

    private func anchor(_ label: String, value: String?) -> some View {
        HStack {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Spacer()
            Text(value ?? String(localized: "chapter_editor.drift_anchor_none"))
                .font(.caption.monospacedDigit())
        }
    }
}
