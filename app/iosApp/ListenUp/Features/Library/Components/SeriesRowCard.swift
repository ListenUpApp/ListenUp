import SwiftUI
import Shared

/// Standalone series row card for the iPhone Series list: an overlapping cover stack,
/// name + meta, a progress affordance, and a chevron — its own rounded surface.
struct SeriesRowCard: View {
    let series: SeriesRow
    let progress: SeriesProgressState

    var body: some View {
        NavigationLink(value: SeriesDestination(id: series.id)) {
            HStack(spacing: 16) {
                CoverStack(covers: series.covers, size: 76, peek: 17)
                    .heroSource(seriesHeroID(series.id))
                VStack(alignment: .leading, spacing: 2) {
                    Text(series.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Text(series.meta)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    SeriesProgressBadge(state: progress).padding(.top, Spacing.xs)
                }
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(Spacing.m)
            .background(RoundedRectangle(cornerRadius: Radius.l, style: .continuous).fill(Color.luSurface2))
            .stackedEdges(series.isParent, cornerRadius: Radius.l)
            .overlay(RoundedRectangle(cornerRadius: Radius.l, style: .continuous).stroke(Color.luSeparator, lineWidth: 0.5))
        }
        .buttonStyle(.pressScaleCard)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(series.name)
        .accessibilityValue(series.meta)
    }
}
