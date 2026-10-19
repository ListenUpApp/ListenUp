import SwiftUI

/// An activity-feed row: the actor's avatar (its own link to their profile), then the shared sentence —
/// "**who** predicate **highlight** suffix", the highlight in coral — over a relative time, as one link
/// to the row's subject: its book, its shelf, or for a row about neither, the actor (spec X27–X32).
/// A book row shows the book's cover trailing — or, at accessibility text sizes, under the sentence, so
/// the sentence keeps the row's width (spec §2.8 item 4).
///
/// Its further actions (`ActivityRowActions`) open from a touch-and-hold context menu with the row as
/// its preview, and are VoiceOver custom actions, so none needs a touch-and-hold (HIG, Context menus).
/// "Add to Shelf" shelves that one book through the screen's `selection`.
struct ActivityRow: View {
    let item: ActivityRowItem
    var now = Date()
    /// The screen's selection, for Add to Shelf; nil leaves that action out.
    var selection: BookSelectionObserver?

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.openActivitySubject) private var openSubject

    private var actions: [ActivityRowActions.Action] {
        ActivityRowActions.actions(
            for: item,
            canShelve: ActivityRowActions.canShelve(
                hasSelection: selection != nil,
                isSelecting: selection?.isSelecting == true
            )
        )
    }

    /// Pure: the cover moves under the sentence once text reaches the accessibility sizes.
    static func placesCoverBelow(at size: DynamicTypeSize) -> Bool { size.isAccessibilitySize }

    /// Relative timestamp, floored to the past. A just-created event whose server `occurredAt` sits a
    /// few seconds ahead of the device clock would otherwise read "in 3 seconds"; we clamp sub-minute
    /// ages to "Just now" and never let the formatter phrase a future instant — matching Android's
    /// floored `relativeTime`.
    private var timeLabel: String {
        if item.occurredAt >= now.addingTimeInterval(-60) {
            return String(localized: "discover.time_ago_now")
        }
        return DiscoverCopy.lastActive(item.occurredAt, relativeTo: now)
    }

    private var profileLabel: String {
        item.isYou
            ? String(localized: "discover.activity_your_profile_a11y")
            : String(format: String(localized: "discover.activity_profile_a11y"), item.who)
    }

    var body: some View {
        HStack(spacing: Spacing.s) {
            // The avatar is its own tap target → the actor's profile. Kept a sibling of (not nested
            // inside) the subject link below, since nested NavigationLinks don't compose.
            NavigationLink(value: ProfileDestination(userId: item.userId)) {
                UserAvatarView(userId: item.userId, fallbackName: item.actorName)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(profileLabel)
            .accessibilityAddTraits(.isLink)

            detail
                .modifier(RowActionsMenu(actions: actions, item: item, perform: perform) {
                    // HIG: the preview shows what the menu acts on — the row itself.
                    detailContent.padding(Spacing.m).frame(width: 320)
                })
        }
        .padding(.vertical, Spacing.xxs)
    }

    private func perform(_ action: ActivityRowActions.Action) {
        switch action {
        case .openBook(let id): openSubject(.book(id))
        case .openShelf(let id): openSubject(.shelf(id))
        case .viewPerson(let userId): openSubject(.profile(userId))
        case .addToShelf(let id): selection?.addOneBookToShelf(id)
        }
    }

    /// The sentence → the row's subject. `NavigationLink(value:)` needs one concrete type per branch.
    @ViewBuilder private var detail: some View {
        switch item.destination {
        case .book(let id):
            NavigationLink(value: BookDestination(id: id)) { detailContent }
                .buttonStyle(.pressScaleRow)
        case .shelf(let id):
            NavigationLink(value: ShelfDestination(id: id)) { detailContent }
                .buttonStyle(.pressScaleRow)
        case .profile(let userId):
            NavigationLink(value: ProfileDestination(userId: userId)) { detailContent }
                .buttonStyle(.pressScaleRow)
        }
    }

    private var detailContent: some View {
        let coverBelow = Self.placesCoverBelow(at: dynamicTypeSize)
        let layout = coverBelow
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.xs))
            : AnyLayout(HStackLayout(spacing: Spacing.s))
        return layout {
            VStack(alignment: .leading, spacing: 2) {
                // The whole sentence, never clamped: its " by {Author}" ending is the point (D5).
                phrase
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)

                Text(timeLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let bookId = item.bookId {
                BookCoverImage(bookId: bookId, coverPath: item.coverPath, coverHash: item.coverHash)
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.s))
                    .accessibilityHidden(true)
            }
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityText)
    }

    /// "**who** predicate **highlight** suffix" — actor semibold, highlight in coral semibold.
    private var phrase: Text {
        let who = Text(item.who).fontWeight(.semibold).foregroundStyle(.primary)
        let predicate = Text(" \(item.predicate)").foregroundStyle(.primary)
        let suffix = Text(item.suffix).foregroundStyle(.primary)
        if let highlight = item.highlight, !highlight.isEmpty {
            let title = Text(highlight).fontWeight(.semibold).foregroundStyle(Color.luTint)
            return Text("\(who)\(predicate) \(title)\(suffix)")
        }
        return Text("\(who)\(predicate)\(suffix)")
    }

    var sentence: String {
        if let highlight = item.highlight, !highlight.isEmpty {
            return "\(item.who) \(item.predicate) \(highlight)\(item.suffix)"
        }
        return "\(item.who) \(item.predicate)\(item.suffix)"
    }

    private var accessibilityText: String { "\(sentence), \(timeLabel)" }
}

/// The row's actions as a touch-and-hold context menu over a preview of the row, and as named
/// VoiceOver actions, so the Actions rotor and Full Keyboard Access reach each one without a
/// touch-and-hold. A row with no actions gets neither: an empty menu is worse than none.
private struct RowActionsMenu<Preview: View>: ViewModifier {
    let actions: [ActivityRowActions.Action]
    let item: ActivityRowItem
    let perform: (ActivityRowActions.Action) -> Void
    @ViewBuilder let preview: () -> Preview

    func body(content: Content) -> some View {
        if actions.isEmpty {
            content
        } else {
            content
                .contextMenu {
                    ForEach(actions, id: \.self) { action in
                        Button(
                            ActivityRowActions.title(action, item: item),
                            systemImage: ActivityRowActions.systemImage(action)
                        ) { perform(action) }
                    }
                } preview: {
                    preview()
                }
                .accessibilityActions {
                    ForEach(actions, id: \.self) { action in
                        Button(ActivityRowActions.title(action, item: item)) { perform(action) }
                    }
                }
        }
    }
}
