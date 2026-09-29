import SwiftUI
import Shared

/// The subtree-scope toggle plus the wrap of sub-genre chips beneath it, shown on a genre
/// destination page between the header and the book grid. Callers gate on `observer.hasSubs` (a
/// leaf genre has no scope to toggle and no children to list). Mirrors Android's
/// `SubtreeToggleSection`.
struct GenreSubtreeSection: View {
    let observer: GenrePageObserver

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            toggleRow

            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "genre_destination.sub_genres_label"))
                    .font(.caption.weight(.bold))
                    .tracking(0.6)
                    .textCase(.uppercase)
                    .foregroundStyle(.secondary)
                Text(String(localized: "genre_destination.tap_to_narrow"))
                    .font(.caption2)
                    .foregroundStyle(Color.secondary.opacity(0.7))
            }

            FlowLayout(spacing: 8) {
                ForEach(observer.subGenres) { sub in
                    NavigationLink(value: GenreDestination(genreId: sub.id, genreName: sub.name)) {
                        chip(sub)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var toggleRow: some View {
        HStack(spacing: 14) {
            RoundedRectangle(cornerRadius: Radius.m, style: .continuous)
                .fill(observer.includeSubGenres ? Color.listenUpOrange.opacity(0.18) : Color.luFill)
                .frame(width: 40, height: 40)
                .overlay {
                    Image(systemName: "point.3.connected.trianglepath.dotted")
                        .font(.system(size: 17, weight: .semibold)) // decorative fixed size
                        .foregroundStyle(observer.includeSubGenres ? Color.listenUpOrange : Color.primary)
                }
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(String(localized: "genre_destination.include_sub_genres"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(scopeSubtitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }

            Spacer()

            Toggle(
                "",
                isOn: Binding(
                    get: { observer.includeSubGenres },
                    set: { _ in observer.toggleIncludeSubGenres() }
                )
            )
            .labelsHidden()
        }
        .padding(.horizontal, Spacing.m)
        .padding(.vertical, Spacing.s)
        .background(Color.luFill.opacity(0.5), in: RoundedRectangle(cornerRadius: Radius.l, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityLabel(String(localized: "genre_destination.include_sub_genres"))
        .accessibilityValue(Text(scopeSubtitle))
        .accessibilityAddTraits(.isButton)
    }

    private func chip(_ sub: SubGenreRow) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(sub.hue)
                .frame(width: 8, height: 8)
                .accessibilityHidden(true)
            Text(sub.name)
                .font(.caption.weight(.medium))
                .foregroundStyle(.primary)
            if sub.bookCount > 0 {
                Text("\(sub.bookCount)")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, Spacing.xs)
                    .padding(.vertical, 2)
                    .background(Color.luFill, in: Capsule())
            }
        }
        .padding(.horizontal, Spacing.s)
        .padding(.vertical, Spacing.xs)
        .background {
            Capsule().strokeBorder(Color.luSeparator, lineWidth: 1.2)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(sub.name), \(sub.bookCount)")
        .accessibilityAddTraits(.isButton)
    }

    /// The toggle's contextual subtitle — what "on"/"off" actually means for this genre right now.
    private var scopeSubtitle: String {
        if observer.includeSubGenres {
            return String(
                format: String(localized: "genre_destination.scope_on"),
                observer.bookCount, observer.name, observer.subGenres.count
            )
        }
        let namedCount = 2
        let named = observer.subGenres.prefix(namedCount).map(\.name).joined(separator: ", ")
        let excludedCount = observer.subGenres.count - namedCount
        let summary = excludedCount > 0
            ? named + String(
                format: String(localized: "genre_destination.scope_off_excluded_suffix"),
                excludedCount
            )
            : named
        return String(
            format: String(localized: "genre_destination.scope_off"),
            observer.bookCount, observer.name, summary
        )
    }
}
