import SwiftUI

/// The earlier-books offer (#1540) as a Form section between who you are and Sync: the offer, the send in
/// progress, then what it came to. Nothing for `.none` or `.available` — `.available` is the quiet row in
/// Sync (`HardcoverEarlierBooksRow`).
///
/// HIG, Progress indicators: a determinate `ProgressView(value:)` for work whose size is known, read as
/// "23 of 74". HIG, Buttons: the likely action, Send, carries the weight; Not Now stays plain.
struct HardcoverHistorySection: View {
    let history: HardcoverHistoryModel
    let onSend: () -> Void
    let onDismiss: () -> Void
    let onShowNeedsMatch: () -> Void

    var body: some View {
        switch history {
        case .offer(let books):
            offerSection(books: books)
        case .sending(let sent, let total):
            sendingSection(sent: sent, total: total)
        case .done(let sent, let needsMatch):
            doneSection(sent: sent, needsMatch: needsMatch)
        case .none, .available:
            EmptyView()
        }
    }

    private func offerSection(books: Int) -> some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: Spacing.xxs) {
                    Text(String(localized: "hardcover.history_offer_title"))
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    Text(HardcoverHistoryText.offerBody(books: books))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundStyle(Color.luTint)
                    .accessibilityHidden(true)
            }
            .padding(.vertical, Spacing.xxs)
            Button(action: onSend) {
                Text(HardcoverHistoryText.sendTitle(books: books))
                    .fontWeight(.semibold)
                    .foregroundStyle(Color.luTint)
            }
            Button(action: onDismiss) {
                Text(HardcoverHistoryText.notNowTitle())
                    .foregroundStyle(Color.luTint)
            }
        }
    }

    private func sendingSection(sent: Int, total: Int) -> some View {
        Section {
            VStack(alignment: .leading, spacing: Spacing.s) {
                Label {
                    Text(HardcoverHistoryText.sendingTitle(sent: sent, total: total))
                        .monospacedDigit()
                } icon: {
                    Image(systemName: "clock.arrow.circlepath")
                        .foregroundStyle(Color.luTint)
                        .accessibilityHidden(true)
                }
                ProgressView(value: Double(sent), total: Double(max(total, 1)))
                    .tint(Color.luTint)
                    .accessibilityLabel(String(localized: "hardcover.history_progress_label"))
                    .accessibilityValue(HardcoverHistoryText.progressValue(sent: sent, total: total))
            }
            .padding(.vertical, Spacing.xxs)
        } footer: {
            Text(String(localized: "hardcover.history_sending_note"))
        }
    }

    private func doneSection(sent: Int, needsMatch: Int) -> some View {
        Section {
            Label {
                Text(HardcoverHistoryText.doneTitle(sent: sent, needsMatch: needsMatch))
                    .font(.headline)
            } icon: {
                Image(systemName: "checkmark.circle")
                    .foregroundStyle(.green)
                    .accessibilityHidden(true)
            }
            if needsMatch > 0 {
                // It moves you to Needs a match, so it reads as navigation: a chevron (HIG, Lists and tables).
                Button(action: onShowNeedsMatch) {
                    HStack {
                        Text(HardcoverHistoryText.needsMatchLine(needsMatch))
                            .foregroundStyle(Color.primary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.tertiary)
                            .accessibilityHidden(true)
                    }
                }
            }
            Button(action: onDismiss) {
                Text(String(localized: "common.done"))
                    .foregroundStyle(Color.luTint)
            }
            .accessibilityLabel(String(localized: "common.dismiss"))
            // Voice Control users say what they see: "Done" works as well as "Dismiss".
            .accessibilityInputLabels([String(localized: "common.done"), String(localized: "common.dismiss")])
        }
    }
}

/// "Send Earlier Books": the quiet row in Sync after Not Now, while earlier books wait. It sends in place,
/// so it is a tinted button row, never a chevron (which would promise another screen).
struct HardcoverEarlierBooksRow: View {
    let books: Int
    let onSend: () -> Void

    var body: some View {
        Button(action: onSend) {
            Label {
                VStack(alignment: .leading, spacing: 1) {
                    Text(HardcoverHistoryText.rowTitle())
                        .foregroundStyle(Color.luTint)
                    Text(HardcoverHistoryText.availableDetail(books: books))
                        .font(.subheadline)
                        // `Color`, not the hierarchical style: inside a Button label `.secondary` is the tint
                        // at secondary opacity (2.19:1 on white), not the grey it reads as everywhere else.
                        .foregroundStyle(Color.secondary)
                }
            } icon: {
                Image(systemName: "clock.arrow.circlepath")
                    .foregroundStyle(Color.luTint)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityLabel(String(localized: "hardcover.history_row_send_label"))
        .accessibilityValue(HardcoverHistoryText.availableDetail(books: books))
    }
}
