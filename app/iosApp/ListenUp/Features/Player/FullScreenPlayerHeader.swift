import SwiftUI

/// Collapse · where the audio is · More. On iPad's wide player it is titled "Now Playing" (the `NP-iPad` board; HIG,
/// Layout: name the screen where there is room), the status centred in what is left, and More leaves Go to Series
/// out because the series is a link above the title.
struct FullScreenPlayerHeader: View {
    static let height: CGFloat = 44

    let observer: PlayerObserver
    let showsTitle: Bool
    let onDismiss: () -> Void
    let onViewDetails: () -> Void
    let onViewSeries: (String) -> Void
    let onViewContributor: (String) -> Void
    let onShowBoostPicker: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Button(action: onDismiss) {
                headerGlyph("chevron.down")
            }
            .buttonStyle(.plain)
            .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
            .accessibilityLabel(String(localized: "player.collapse"))

            if showsTitle {
                Text(String(localized: "player.np_now_playing_ios"))
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .padding(.leading, Spacing.xs)
                    .accessibilityAddTraits(.isHeader)
            }

            Spacer(minLength: Spacing.xs)

            // Only news: plain status text, not a chip (HIG). Downloaded, streaming
            // and casting say nothing; AirPlay's caption names a device.
            if let statusText = observer.statusText {
                Text(statusText)
                    .font(.footnote)
                    .foregroundStyle(Color.luOnWashSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(1)
            }

            Spacer(minLength: Spacing.xs)

            moreMenu(includesSeries: !showsTitle)
        }
        .frame(minHeight: Self.height)
        .padding(.horizontal, Spacing.m)
    }

    /// A 36pt glass disc — a floating control, where Liquid Glass belongs — in a 44pt hit area.
    /// Like a navigation bar's buttons, the glyph keeps its size at large text settings and offers
    /// the large content viewer instead (HIG, Accessibility).
    private func headerGlyph(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 17, weight: .semibold)) // decorative fixed size
            .foregroundStyle(.primary)
            .frame(width: 36, height: 36)
            .glassControl(in: Circle())
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
            .accessibilityShowsLargeContentViewer()
    }

    /// The shared More menu: the places, then Close Book in its own section, destructive (HIG, Menus). The phone's
    /// title block names the narrators only, so More carries Go to Author (a submenu for several, HIG Menus); iPad's
    /// wide player shows the authors and the series as links, so its More has neither. Never Go to Narrator: the
    /// narrator line's names are the way there.
    private func moreMenu(includesSeries: Bool) -> some View {
        Menu {
            let sections = includesSeries ? observer.menu : observer.menuWithoutSeries
            ForEach(Array(sections.enumerated()), id: \.offset) { _, section in
                Section {
                    ForEach(section.rows, id: \.self) { row in
                        if row == .chooseAuthor {
                            Menu {
                                ForEach(NowPlayingTitleBlock.menuRows(observer.authors)) { author in
                                    Button { onViewContributor(author.id) } label: {
                                        Text(author.name)
                                        if let books = author.books { Text(books) }
                                    }
                                }
                            } label: {
                                Label(row.title, systemImage: row.systemImage)
                            }
                        } else {
                            Button(role: section.isDestructive ? .destructive : nil) { perform(row) } label: {
                                Label(row.title, systemImage: row.systemImage)
                            }
                        }
                    }
                }
            }
        } label: {
            headerGlyph("ellipsis")
        }
        .frame(minWidth: TapTarget.minimum, minHeight: TapTarget.minimum)
        .contentShape(Rectangle())
        .accessibilityLabel(String(localized: "player.more_options"))
    }

    private func perform(_ row: PlayerMenuModel.Row) {
        switch row {
        case .goToBook: onViewDetails()
        case .goToSeries(let seriesId): onViewSeries(seriesId)
        case .goToAuthor(let authorId): onViewContributor(authorId)
        case .chooseAuthor: break // a submenu, never a button
        case .openPdf: observer.openCurrentBookPdf()
        case .volumeBoost: onShowBoostPicker()
        case .closeBook: observer.closeBook()
        }
    }
}
