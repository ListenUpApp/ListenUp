import SwiftUI

/// One part of a drawn series path: a link to a series, or the "…" that stands in for the middle of
/// a deep path until it is expanded.
enum SeriesPathPart: Identifiable, Hashable {
    /// `label` is what is drawn — the name, plus " #1" on the last part of a Book Detail line.
    case link(id: String, label: String)
    case fold

    var id: String {
        switch self {
        case .link(let id, _): id
        case .fold: "…fold"
        }
    }
}

/// Builds the parts of a path line. Pure, so the fold is unit-tested.
enum SeriesPathModel {
    /// A path this deep (ancestors) folds its middle: four levels or more.
    static let foldingDepth = 3

    /// The breadcrumb above a child series: every ancestor, root first. Never folded — the page's
    /// own title follows it.
    static func breadcrumb(_ ancestors: [SeriesCrumbItem]) -> [SeriesPathPart] {
        ancestors.map { .link(id: $0.id, label: $0.name) }
    }

    /// A Book Detail line — "Cosmere › Mistborn › Mistborn Era 1 #1". At four levels or more, and
    /// until `expanded`, the middle folds: "Cosmere › … › Era 1 #1".
    static func bookLine(
        ancestors: [SeriesCrumbItem],
        seriesId: String,
        seriesName: String,
        sequence: String?,
        expanded: Bool
    ) -> [SeriesPathPart] {
        let leafLabel = sequence.map { "\(seriesName) #\($0)" } ?? seriesName
        let leaf = SeriesPathPart.link(id: seriesId, label: leafLabel)
        let crumbs = breadcrumb(ancestors)
        guard ancestors.count >= foldingDepth, !expanded, let root = crumbs.first else { return crumbs + [leaf] }
        return [root, .fold, leaf]
    }
}

/// A series path drawn as links with "›" between them, wrapping only at the separators so no name is
/// ever cut. Every name is a link to that series at a full 44pt target; the separators are silent
/// to VoiceOver; the whole path is one container labelled "Series path".
struct SeriesPathView: View {
    let parts: [SeriesPathPart]
    var alignment: HorizontalAlignment = .center
    var font: Font = .subheadline.weight(.semibold)
    /// Called when the "…" is tapped; the presenter expands the line in place.
    var onExpand: () -> Void = {}

    var body: some View {
        FlowLayout(spacing: 0, alignment: alignment) {
            ForEach(Array(parts.enumerated()), id: \.element.id) { index, part in
                HStack(spacing: 0) {
                    if index > 0 {
                        Text(SeriesHierarchyText.separator)
                            .foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                    }
                    partView(part)
                }
            }
        }
        .font(font)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text(String(localized: "series.path_a11y")))
    }

    @ViewBuilder
    private func partView(_ part: SeriesPathPart) -> some View {
        switch part {
        case .link(let id, let label):
            NavigationLink(value: SeriesDestination(id: id)) {
                Text(label)
                    .foregroundStyle(Color.luTint)
                    .multilineTextAlignment(.leading)
                    .frame(minHeight: TapTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        case .fold:
            Button(action: onExpand) {
                Text(verbatim: "…")
                    .foregroundStyle(Color.luTint)
                    .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(String(localized: "series.path_expand_a11y")))
        }
    }
}
