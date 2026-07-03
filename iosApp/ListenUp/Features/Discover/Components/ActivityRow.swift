import SwiftUI

/// An activity-feed row: an initials avatar, "**who** *action* **book**" with the book in
/// coral, and a relative timestamp ("2h ago"). When the activity carries a book the row is
/// tappable and navigates to the book's detail.
struct ActivityRow: View {
    let item: ActivityRowItem

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter
    }()

    private var timeLabel: String {
        Self.relativeFormatter.localizedString(for: item.occurredAt, relativeTo: Date())
    }

    /// Timestamp, with the listened duration appended as a middot-separated detail when present
    /// ("2h ago · 1h 5m").
    private var detailLabel: String {
        if let duration = item.duration {
            return "\(timeLabel) · \(duration)"
        }
        return timeLabel
    }

    var body: some View {
        if let bookId = item.bookId {
            NavigationLink(value: BookDestination(id: bookId)) {
                content
            }
            .buttonStyle(.pressScaleRow)
        } else {
            content
        }
    }

    private var content: some View {
        HStack(spacing: 13) {
            UserAvatarView(userId: item.userId, fallbackName: item.who, avatarColor: item.avatarColor)

            VStack(alignment: .leading, spacing: 2) {
                phrase
                    .font(.subheadline)
                    .lineLimit(2)

                Text(detailLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }

    /// "**who** action **book**" — actor semibold, book in coral semibold.
    private var phrase: Text {
        let who = Text(item.who).fontWeight(.semibold).foregroundStyle(.primary)
        let action = Text(" \(item.action) ").foregroundStyle(.primary)
        if let book = item.book, !book.isEmpty {
            let title = Text(book).fontWeight(.semibold).foregroundStyle(Color.luTint)
            return Text("\(who)\(action)\(title)")
        }
        return Text("\(who)\(action)")
    }

    private var accessibilityText: String {
        if let book = item.book, !book.isEmpty {
            return "\(item.who) \(item.action) \(book), \(detailLabel)"
        }
        return "\(item.who) \(item.action), \(detailLabel)"
    }
}
