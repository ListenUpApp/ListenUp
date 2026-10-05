import SwiftUI

/// One sub-series on a parent series' page: cover, name, "8 books · 2 finished" and a bar, as a
/// `List` row that opens the sub-series. A series with sub-series of its own gets a stacked edge
/// behind its cover and a "2 series" label. One VoiceOver element whose progress reads as counts.
struct ChildSeriesRow: View {
    let card: ChildSeriesCard

    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationLink(value: SeriesDestination(id: card.id)) {
            // At accessibility sizes the cover sits above the text, so the name is never squeezed.
            let layout = typeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: Spacing.s))
                : AnyLayout(HStackLayout(alignment: .center, spacing: 14))
            layout {
                cover
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                    if let hint = card.subSeriesHint {
                        Text(hint)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.luTint)
                    }
                    Text(card.meta)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    ProgressBar(progress: card.progress)
                        .frame(height: 4)
                        .padding(.top, Spacing.xxs)
                }
            }
            .padding(.vertical, Spacing.xxs)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(card.accessibilityLabel)
    }

    private var cover: some View {
        BookCoverImage(coverPath: card.coverPath)
            .frame(width: 54, height: 54)
            .clipShape(RoundedRectangle(cornerRadius: Radius.s))
            .stackedEdges(card.subSeriesCount > 0, cornerRadius: Radius.s)
            .padding(.bottom, card.subSeriesCount > 0 ? 6 : 0)
            .accessibilityHidden(true)
    }
}

/// The heading over one group of a parent page's books: the sub-series' name (a link to its page),
/// its path when nested, or "Also in Cosmere"; the count; and, for a sub-series, a fold toggle.
struct SeriesGroupHeader: View {
    let group: SeriesBookGroup
    let onToggle: () -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: Spacing.xs) {
            if group.kind == .subSeries {
                NavigationLink(value: SeriesDestination(id: group.seriesId)) { heading }
                    .buttonStyle(.plain)
            } else {
                heading
            }
            Spacer(minLength: Spacing.xs)
            Text(group.countLabel)
                .font(.subheadline)
                .foregroundStyle(.secondary)
            if group.isCollapsible {
                Button(action: onToggle) {
                    Image(systemName: "chevron.down")
                        .font(.subheadline.weight(.semibold))
                        .rotationEffect(.degrees(group.isCollapsed ? -90 : 0))
                        .foregroundStyle(Color.luTint)
                        .minimumTapTarget(visualSize: 20)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: group.isCollapsed ? "common.expand" : "common.collapse"))
            }
        }
        .textCase(nil)
    }

    private var heading: some View {
        Text(group.heading)
            .font(group.depth >= 2 ? .headline : .title3.bold())
            .foregroundStyle(group.depth >= 2 ? .secondary : .primary)
            .multilineTextAlignment(.leading)
            .accessibilityAddTraits(.isHeader)
    }
}

extension View {
    /// Two offset layers peeking out under a cover or card — the hint that it holds more series.
    /// Decorative: hidden from VoiceOver, whose label already says "2 series".
    func stackedEdges(_ isStacked: Bool, cornerRadius: CGFloat) -> some View {
        background(alignment: .top) {
            if isStacked {
                ZStack {
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .fill(Color.luSeparator.opacity(0.5))
                        .padding(.horizontal, Spacing.xs)
                        .offset(y: 6)
                    RoundedRectangle(cornerRadius: cornerRadius)
                        .fill(Color.luSeparator)
                        .padding(.horizontal, Spacing.xxs)
                        .offset(y: 3)
                }
                .accessibilityHidden(true)
            }
        }
    }
}
