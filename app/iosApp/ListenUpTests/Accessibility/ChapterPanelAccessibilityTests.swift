import SwiftUI
import Testing
import UIKit
@testable import ListenUp
import ListenupContract
@preconcurrency import Shared

/// Now Playing on iPad (player spec §3.11, §5.3, §8; the `NP-iPad` board), read the way VoiceOver and the eye meet it:
/// the titled header, the series link, the always-visible chapter panel that opens on your chapter and follows only
/// while you're watching it, its ink on every wash, and the large-text and multitasking fallbacks.
@MainActor
@Suite("Now Playing on iPad: the chapter panel", .serialized)
struct ChapterPanelAccessibilityTests {
    /// The board's landscape iPad, and the same iPad upright.
    nonisolated static let landscape = CGSize(width: 1180, height: 820)
    nonisolated static let portrait = CGSize(width: 834, height: 1150)
    /// Half of a 13-inch iPad in Split View: the phone layout, with the chapter sheet.
    nonisolated static let splitHalf = CGSize(width: 683, height: 1000)

    /// 68 one-minute chapters, playing, `current` (0-based) with 40 seconds of it left.
    static func state(current: Int = 38) -> PlayerUiStateLoaded {
        .fixture(phase: PlayerPhasePlaying.shared, currentIndex: current, chapterCount: 68, chapterDurationMs: 60_000)
    }

    static func progress(current: Int = 38) -> PlayerProgressRow {
        PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 20_000, chapterDurationMs: 60_000, bookPositionMs: Int64(current) * 60_000 + 20_000,
            bookDurationMs: 68 * 60_000))
    }

    static func observer(current: Int = 38) -> PlayerObserver {
        .detached(state(current: current), progress: progress(current: current))
    }

    private func hostPlayer(
        _ observer: PlayerObserver = Self.observer(), size: CGSize = Self.landscape,
        dynamicTypeSize: DynamicTypeSize = .large, appearance: UIUserInterfaceStyle = .light,
        onViewSeries: @escaping (String) -> Void = { _ in }
    ) async -> HostedView {
        await HostedView(
            FullScreenPlayerView(observer: observer, onViewSeries: onViewSeries), size: size,
            dynamicTypeSize: dynamicTypeSize, appearance: appearance)
    }

    // MARK: - Words

    /// The current row's time left as VoiceOver says it: 40 seconds, in words.
    private var left: String { MiniPlayerAccessoryModel.spokenDuration(ms: 40_000) }
    private var nowPlaying: String { String(localized: "player.np_now_playing_ios") }
    private var backToCurrent: String { String(localized: "player.np_back_to_current_ios") }
    private var chapters: String { String(localized: "player.chapters") }
    private var seriesLink: String { "The Stormlight Archive · Book 1" }

    private func currentLabel(_ index: Int) -> String {
        String(format: String(localized: "player.sheet_chapter_current"), index + 1, "Chapter \(index + 1)", left)
    }

    private func playFromLabel(_ index: Int) -> String {
        String(format: String(localized: "player.sheet_chapter_play_from"), index + 1, "Chapter \(index + 1)", "1:00")
    }

    // MARK: - Geometry

    /// The panel's list: the scroll view the current row sits in, the narrowest one that holds it.
    private func panelScroll(holding row: AccessibilityStop, in hosted: HostedView) -> UIScrollView? {
        hosted.scrollViews
            .filter { $0.convert($0.bounds, to: nil).insetBy(dx: -1, dy: -10_000).contains(row.frame.center) }
            .min { $0.bounds.width < $1.bounds.width }
    }

    private func isCentred(_ row: AccessibilityStop, in scroll: UIScrollView) -> Bool {
        abs(row.frame.midY - scroll.convert(scroll.bounds, to: nil).midY) <= row.frame.height
    }

    // MARK: - The header (P3) and the series (P10)

    @Test(arguments: [Self.landscape, Self.portrait])
    func theHeaderIsTitledNowPlaying(size: CGSize) async throws {
        let hosted = await hostPlayer(size: size)
        defer { hosted.close() }
        let title = try #require(hosted.stop(labelled: nowPlaying), "\(hosted.tree)")
        #expect(title.isHeader)
        let labels = hosted.stops.prefix(3).map(\.label)
        #expect(labels.prefix(2) == [String(localized: "player.collapse"), nowPlaying], "\(labels)")
    }

    @Test func aSplitViewHalfKeepsThePhoneHeaderAndTheChaptersButton() async throws {
        let hosted = await hostPlayer(size: Self.splitHalf)
        defer { hosted.close() }
        #expect(hosted.stop(labelled: nowPlaying) == nil, "\(hosted.tree)")
        let button = try #require(hosted.stops(labelled: chapters).first { $0.isButton }, "\(hosted.tree)")
        #expect(button.frame.height >= 44)
    }

    @Test func theSeriesIsALinkAboveTheTitle() async throws {
        let opened = OpenedSeries()
        let hosted = await hostPlayer(onViewSeries: { opened.ids.append($0) })
        defer { hosted.close() }
        let series = try #require(hosted.stop(labelled: seriesLink), "\(hosted.tree)")
        let title = try #require(hosted.stop(labelled: "The Way of Kings"), "\(hosted.tree)")
        #expect(series.isButton)
        #expect(series.frame.maxY <= title.frame.minY + 1, "series \(series.frame) title \(title.frame)")
        #expect(hosted.activate(labelled: seriesLink))
        #expect(opened.ids == ["series-1"])
    }

    @Test(arguments: [Self.landscape, Self.portrait])
    func chaptersLeavesTheSettingsRowBesideThePanel(size: CGSize) async throws {
        let hosted = await hostPlayer(size: size)
        defer { hosted.close() }
        let named = hosted.stops(labelled: chapters)
        #expect(!named.isEmpty, "\(hosted.tree)")
        #expect(named.allSatisfy { $0.isHeader && !$0.isButton }, "\(named)")
        #expect(hosted.stop(labelled: String(localized: "player.speed")) != nil)
    }

    /// The board puts the cover beside the controls in landscape; upright there is no room, so it stacks.
    @Test func theCoverSitsBesideTheControlsInLandscapeAndAboveThemUpright() async throws {
        let wide = await hostPlayer(size: Self.landscape)
        let wideTitle = try #require(wide.stop(labelled: "The Way of Kings"), "\(wide.tree)")
        wide.close()
        #expect(wideTitle.frame.minX >= PlayerLayoutMode.coverBesideRange.lowerBound, "\(wideTitle.frame)")

        let upright = await hostPlayer(size: Self.portrait)
        defer { upright.close() }
        let uprightTitle = try #require(upright.stop(labelled: "The Way of Kings"), "\(upright.tree)")
        #expect(uprightTitle.frame.minX < 120, "\(uprightTitle.frame)")
        #expect(uprightTitle.frame.minY >= PlayerLayoutMode.coverBesideRange.lowerBound, "\(uprightTitle.frame)")
    }

    // MARK: - Names and targets (§8)

    @Test func theCurrentRowIsSelectedAndTheOthersOfferToPlay() async throws {
        let hosted = await hostPlayer()
        defer { hosted.close() }
        let current = try #require(hosted.stop(labelled: currentLabel(38)), "\(hosted.tree)")
        #expect(current.isSelected)
        let next = try #require(hosted.stop(labelled: playFromLabel(39)), "\(hosted.tree)")
        #expect(next.isButton)
        // The row bar is drawn, not spoken: the current row is still one stop.
        let insideCurrent = hosted.stops.filter { current.frame.insetBy(dx: 2, dy: 2).contains($0.frame.center) }
        #expect(insideCurrent.count == 1, "\(insideCurrent)")
    }

    /// The row shows "8 min" and says "8 minutes": VoiceOver never reads an abbreviation as letters (spec §8).
    @Test func theCurrentRowSaysItsTimeLeftInWords() {
        let english = Locale(identifier: "en_US")
        #expect(MiniPlayerAccessoryModel.spokenDuration(ms: 510_000, locale: english) == "8 minutes")
        #expect(MiniPlayerAccessoryModel.spokenDuration(ms: 3_930_000, locale: english) == "1 hour, 5 minutes")
        #expect(MiniPlayerAccessoryModel.spokenDuration(ms: 42_000, locale: english) == "42 seconds")
        let label = ChapterRow.spokenLabel(number: 6, title: "Bridge Four", durationMs: 1_235_000, remainingMs: 510_000)
        let spoken = String(
            format: String(localized: "player.sheet_chapter_current"), 6, "Bridge Four",
            MiniPlayerAccessoryModel.spokenDuration(ms: 510_000))
        #expect(label == spoken)
    }

    /// A progress tick reaches only the current row's leaves (its time-left line, its spoken name and its bar), never
    /// the row's own body, so the panel and its other rows do not redraw four times a second.
    @Test func aProgressTickReachesOnlyTheCurrentRowsLeaves() {
        let reads = ProgressReads()
        let row = ChapterRow(
            number: 6, title: "Bridge Four", durationMs: 1_235_000, isCurrent: true, isPlaying: true,
            remainingMs: {
                reads.count += 1
                return 510_000
            },
            chapterFraction: {
                reads.count += 1
                return 0.5
            },
            tint: .luTint, onTap: {})
        _ = row.body
        #expect(reads.count == 0)
    }

    /// And the leaves still follow the tick: the current row's name says the new time left.
    @Test func aProgressTickRenamesTheCurrentRow() async throws {
        let observer = Self.observer()
        let hosted = await HostedView(
            NowPlayingChapterPanel(observer: observer).frame(width: PlayerLayoutMode.panelWidth),
            size: CGSize(width: 400, height: 760))
        defer { hosted.close() }
        await hosted.settle()
        #expect(hosted.stop(labelled: currentLabel(38)) != nil, "\(hosted.tree)")
        observer.replaceDetachedState(Self.state(), progress: PlayerProgressRow(PlayerProgress(
            chapterPositionMs: 50_000, chapterDurationMs: 60_000, bookPositionMs: 38 * 60_000 + 50_000,
            bookDurationMs: 68 * 60_000)))
        await hosted.settle()
        let tenLeft = String(
            format: String(localized: "player.sheet_chapter_current"), 39, "Chapter 39",
            MiniPlayerAccessoryModel.spokenDuration(ms: 10_000))
        #expect(hosted.stop(labelled: tenLeft) != nil, "\(hosted.tree)")
    }

    @Test func everyControlHasAFullTapTarget() async throws {
        let hosted = await hostPlayer()
        defer { hosted.close() }
        let labels = [
            String(localized: "player.collapse"), String(localized: "player.more_options"), seriesLink,
            currentLabel(38), playFromLabel(39)
        ]
        for label in labels {
            let stop = try #require(hosted.stop(labelled: label), "\(label)\n\(hosted.tree)")
            #expect(stop.frame.width >= 44 && stop.frame.height >= 44, "\(label): \(stop.frame)")
        }
    }

    // MARK: - Opens centred, follows, holds, and goes back (§3.11)

    @Test func thePanelOpensCentredOnTheCurrentChapter() async throws {
        let hosted = await hostPlayer()
        defer { hosted.close() }
        await hosted.settle()
        let current = try #require(hosted.stop(labelled: currentLabel(38)), "\(hosted.tree)")
        let scroll = try #require(panelScroll(holding: current, in: hosted))
        #expect(isCentred(current, in: scroll), "row \(current.frame) list \(scroll.convert(scroll.bounds, to: nil))")
        #expect(hosted.stop(labelled: backToCurrent) == nil, "\(hosted.tree)")
    }

    @Test func whileYouCanSeeTheCurrentRowTheListFollowsTheNextChapter() async throws {
        let observer = Self.observer(current: 38)
        let hosted = await hostPlayer(observer)
        defer { hosted.close() }
        await hosted.settle()
        observer.replaceDetachedState(Self.state(current: 39), progress: Self.progress(current: 39))
        await hosted.settle()
        await hosted.settle()
        let current = try #require(hosted.stop(labelled: currentLabel(39)), "\(hosted.tree)")
        let scroll = try #require(panelScroll(holding: current, in: hosted))
        #expect(isCentred(current, in: scroll), "row \(current.frame) list \(scroll.convert(scroll.bounds, to: nil))")
    }

    // Holding after the reader scrolls away, and the way back, are the shared rule's (`ChapterPanelFollowTests`): the
    // panel's `.scrollPosition(id:)` restores its list after a programmatic UIKit offset change, so a hosted test cannot
    // stand in for a reader's drag. The way back's own button is hosted below, where the list cannot show the whole row.

    // MARK: - Accessibility text sizes (HIG, Typography)

    /// At AX3 the board's iPad (1180 wide) still earns the panel: the panel and the player column both scroll, so nothing
    /// clips.
    @Test func atAccessibilitySizesThePanelAndTheColumnBothScroll() async throws {
        let hosted = await hostPlayer(dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        await hosted.settle()
        let current = try #require(hosted.stop(labelled: currentLabel(38)), "\(hosted.tree)")
        let panel = try #require(panelScroll(holding: current, in: hosted))
        #expect(panel.contentSize.height > panel.bounds.height + 1)
        let panelLeft = panel.convert(panel.bounds, to: nil).minX
        let column = hosted.scrollViews.filter { $0.convert($0.bounds, to: nil).maxX <= panelLeft + 1 }
        #expect(column.contains { $0.contentSize.height > $0.bounds.height + 1 }, "the player column clips")
    }

    /// A list too short to show the whole current row (AX3 in a short window) can never be "following": it offers the way
    /// back at once, as a full-size button that takes the reader there.
    @Test func aListThatCannotShowTheWholeRowOffersTheWayBack() async throws {
        let hosted = await HostedView(
            NowPlayingChapterPanel(observer: Self.observer()).frame(width: PlayerLayoutMode.panelWidth),
            size: CGSize(width: 360, height: 300), dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        await hosted.settle()
        let back = try #require(hosted.stop(labelled: backToCurrent), "\(hosted.tree)")
        #expect(back.isButton)
        #expect(back.frame.width >= 44 && back.frame.height >= 44, "\(back.frame)")
        #expect(hosted.activate(labelled: backToCurrent))
    }

    /// Upright at AX3 the panel no longer fits (834 < 1000): the phone layout, with Chapters one tap away in the row.
    @Test func uprightAtAccessibilitySizesChaptersIsOneTapAway() async throws {
        let hosted = await hostPlayer(size: Self.portrait, dynamicTypeSize: .accessibility3)
        defer { hosted.close() }
        #expect(hosted.stops(labelled: chapters).contains { $0.isButton }, "\(hosted.tree)")
    }

    // MARK: - Ink on the wash (HIG, Accessibility: 4.5:1 for text, 3:1 for graphics)

    private static let washes: [(UIUserInterfaceStyle, [String])] = [
        (.light, NowPlayingWashTests.lightCoverWashes), (.dark, NowPlayingWashTests.darkCoverWashes)
    ]

    /// Every line the panel draws — "Chapters", the summary, the row numbers, titles and durations, "Now Playing · 40 sec
    /// left" on the current row's tint — over the card over the neutral and every cover wash, light and dark, with and
    /// without Increase Contrast.
    @Test(arguments: [UIUserInterfaceStyle.light, .dark], [UIAccessibilityContrast.normal, .high])
    func thePanelsInkReadsOnEveryWash(
        appearance: UIUserInterfaceStyle, contrast: UIAccessibilityContrast
    ) async throws {
        let covers = Self.washes.first { $0.0 == appearance }?.1 ?? []
        let traits = UITraitCollection.appearance(appearance, contrast: contrast)
        let neutral = UIColor(PlayerWashTone.color(for: appearance == .dark ? .dark : .light))
            .resolvedColor(with: traits)
        let washes = [neutral] + covers.map { NowPlayingWashTests.srgb($0).uiColor }
        for wash in washes {
            let hosted = await HostedView(
                NowPlayingChapterPanel(observer: Self.observer())
                    .frame(width: PlayerLayoutMode.panelWidth)
                    .padding(Spacing.l)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color(uiColor: wash).ignoresSafeArea()),
                size: CGSize(width: 400, height: 760), appearance: appearance, contrast: contrast)
            await hosted.settle()
            let context = "\(appearance.rawValue)/\(contrast.rawValue) on \(SRGBColor(wash))"
            measure(hosted, context: context)
            hosted.close()
        }
    }

    private func measure(_ hosted: HostedView, context: String) {
        let drawn = DrawnContrast(hosted, composited: true)
        let summary = String(
            format: String(localized: "player.chapters_count_summary"), 68,
            DurationFormatting.abbreviated(ms: 68 * 60_000))
        for label in [chapters, summary] {
            guard let stop = hosted.stop(labelled: label) else {
                Issue.record("no \(label) in \(context)\n\(hosted.tree)")
                continue
            }
            for line in drawn.textLines(in: stop.frame.insetBy(dx: 0, dy: -2)) {
                #expect(line >= ContrastMinimum.text, "\(label) \(line) \(context)")
            }
        }
        guard let other = hosted.stop(labelled: playFromLabel(39)),
              let current = hosted.stop(labelled: currentLabel(38)) else {
            Issue.record("rows missing in \(context)\n\(hosted.tree)")
            return
        }
        for (name, frame) in [("other", other.frame), ("current", current.frame)] {
            let row = frame.insetBy(dx: 0, dy: 3)
            let number = CGRect(x: row.minX, y: row.minY, width: 46, height: row.height)
            let trailing = CGRect(x: row.maxX - 120, y: row.minY, width: 120, height: row.height)
            for (part, rect) in [("number", number), ("duration", trailing)] {
                let lines = drawn.textLines(in: rect)
                #expect(!lines.isEmpty, "\(name) \(part) drew nothing \(context)")
                for line in lines { #expect(line >= ContrastMinimum.text, "\(name) \(part) \(line) \(context)") }
            }
        }
        // The current row's middle: its title, then "Now Playing · 40 sec left", then the bar (a graphic, below).
        let middle = CGRect(
            x: current.frame.minX + 50, y: current.frame.minY + 3, width: current.frame.width - 180,
            height: current.frame.height - 6)
        let lines = drawn.textLines(in: middle)
        #expect(lines.count >= 2, "\(lines) \(context)")
        for line in lines.prefix(2) { #expect(line >= ContrastMinimum.text, "current row text \(line) \(context)") }
    }

    @Test(arguments: [UIUserInterfaceStyle.light, .dark])
    func theHeaderTitleReadsOnTheWash(appearance: UIUserInterfaceStyle) async throws {
        let hosted = await hostPlayer(appearance: appearance)
        defer { hosted.close() }
        let title = try #require(hosted.stop(labelled: nowPlaying), "\(hosted.tree)")
        let lines = DrawnContrast(hosted, composited: true).textLines(in: title.frame.insetBy(dx: 0, dy: -2))
        #expect(!lines.isEmpty)
        for line in lines { #expect(line >= ContrastMinimum.text, "\(line) in \(appearance.rawValue)") }
    }

    /// The card under every appearance: translucent by default (white 60% / dark 70%, the board), opaque under Increase
    /// Contrast, and opaque under Reduce Transparency (HIG, Accessibility; HIG, Materials).
    @Test func thePanelTurnsOpaqueWhenTheReaderAsksForIt() {
        for traits in UITraitCollection.colorAppearances {
            let reduced = UIColor(NowPlayingChapterPanel.cardFill(reduceTransparency: true)).resolvedColor(with: traits)
            #expect(reduced.cgColor.alpha == 1, "reduce transparency \(traits)")
            let card = UIColor(Color.luPlayerPanel).resolvedColor(with: traits)
            if traits.accessibilityContrast == .high {
                #expect(card.cgColor.alpha == 1, "increase contrast \(traits)")
            } else {
                #expect(card.cgColor.alpha < 1, "the board's translucent card \(traits)")
            }
        }
    }

    /// Text on the opaque card (Reduce Transparency) and on the current row's tint over it.
    @Test func theInkReadsOnTheOpaqueCard() {
        for traits in UITraitCollection.colorAppearances {
            let opaque = UIColor(NowPlayingChapterPanel.cardFill(reduceTransparency: true))
            let card = SRGBColor(opaque, resolvedFor: traits)
            let tint = SRGBColor(UIColor(Color.luTint), resolvedFor: traits)
                .composited(opacity: ChapterRow.currentTintOpacity, over: card)
            for (name, ink) in [("label", UIColor.label), ("quiet", UIColor(Color.luQuietInk)),
                                ("tint ink", UIColor(Color.luTintInk))] {
                let resolved = SRGBColor(ink, resolvedFor: traits)
                #expect(resolved.contrastRatio(against: card) >= ContrastMinimum.text, "\(name) on card \(traits)")
                #expect(resolved.contrastRatio(against: tint) >= ContrastMinimum.text, "\(name) on tint \(traits)")
            }
        }
    }

    /// The current row's chapter bar: its fill against its own track, on the row's tint, over the card over every wash
    /// and over the opaque card, as a meaningful graphic (WCAG 1.4.11; plan C7: a deeper ink, so it reads at 3:1).
    @Test func theRowBarsFillReadsOnItsTrack() {
        for traits in UITraitCollection.colorAppearances {
            let isDark = traits.userInterfaceStyle == .dark
            let neutral = SRGBColor(UIColor(PlayerWashTone.color(for: isDark ? .dark : .light)), resolvedFor: traits)
            let covers = isDark ? NowPlayingWashTests.darkCoverWashes : NowPlayingWashTests.lightCoverWashes
            let washes = [neutral] + covers.map(NowPlayingWashTests.srgb)
            let cards = washes.map { SRGBColor(UIColor(Color.luPlayerPanel), resolvedFor: traits, over: $0) }
                + [SRGBColor(UIColor(NowPlayingChapterPanel.cardFill(reduceTransparency: true)), resolvedFor: traits)]
            let coral = SRGBColor(UIColor(Color.luTint), resolvedFor: traits)
            let fill = SRGBColor(UIColor(ChapterRowBar.fill), resolvedFor: traits)
            for card in cards {
                let row = coral.composited(opacity: ChapterRow.currentTintOpacity, over: card)
                let track = coral.composited(opacity: ChapterRowBar.trackOpacity, over: row)
                let ratio = fill.contrastRatio(against: track)
                #expect(ratio >= ContrastMinimum.glyph, "bar \(ratio) on \(card) in \(traits)")
            }
        }
    }

    // MARK: - Reduce Motion (HIG, Motion)

    @Test func theEqualizerHoldsStillUnderReduceMotionAndWhilePaused() {
        #expect(EqualizerGlyph.shouldPulse(isAnimating: true, reduceMotion: false))
        #expect(!EqualizerGlyph.shouldPulse(isAnimating: true, reduceMotion: true))
        #expect(!EqualizerGlyph.shouldPulse(isAnimating: false, reduceMotion: false))
    }
}

/// Where the series link took the reader.
@MainActor
private final class OpenedSeries {
    var ids: [String] = []
}

/// How many times a row read the playhead.
@MainActor
private final class ProgressReads {
    var count = 0
}

private extension CGRect {
    var center: CGPoint { CGPoint(x: midX, y: midY) }
}
