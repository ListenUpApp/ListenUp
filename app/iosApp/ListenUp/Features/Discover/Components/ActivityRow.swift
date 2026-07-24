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

    /// Relative timestamp, floored to the past. A just-created event whose server `occurredAt` sits a
    /// few seconds ahead of the device clock would otherwise read "in 3 seconds"; we clamp sub-minute
    /// ages to "Just now" and never let the formatter phrase a future instant — matching Android's
    /// floored `relativeTime`.
    private var timeLabel: String {
        let now = Date()
        if item.occurredAt >= now.addingTimeInterval(-60) {
            return String(localized: "discover.time_ago_now")
        }
        return Self.relativeFormatter.localizedString(for: min(item.occurredAt, now), relativeTo: now)
    }

    var body: some View {
        HStack(spacing: 13) {
            // The avatar is its own tap target → the actor's profile. Kept a sibling of (not nested
            // inside) the book link below, since nested NavigationLinks don't compose.
            NavigationLink(value: ProfileDestination(userId: item.userId)) {
                UserAvatarView(userId: item.userId, fallbackName: item.who)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(item.who)

            detail
        }
        .padding(.vertical, 8)
    }

    /// The phrase + timestamp → the book's detail when the activity carries a book; otherwise plain.
    @ViewBuilder private var detail: some View {
        if let bookId = item.bookId {
            NavigationLink(value: BookDestination(id: bookId)) {
                detailContent
            }
            .buttonStyle(.pressScaleRow)
        } else {
            detailContent
        }
    }

    private var detailContent: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
                phrase
                    .font(.subheadline)
                    .lineLimit(2)

                Text(timeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
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
            return "\(item.who) \(item.action) \(book), \(timeLabel)"
        }
        return "\(item.who) \(item.action), \(timeLabel)"
    }
}
