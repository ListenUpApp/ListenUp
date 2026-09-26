import Shared
import SwiftUI

/// The chapter timeline (spec §7.3): the whole-book minimap over the draggable detail lane.
///
/// Draws `ChapterTimelineModel` and hands it gestures: drag a boundary (pull away vertically to slow
/// it — the fine-scrub), drag open lane to pan, pinch or tap the zoom buttons to zoom, drag the
/// minimap to move the window. The behaviour lives in the shared Kotlin lane, not here.
///
/// ⛔ Kept outside the `List`: the fine-scrub is a vertical pull, and inside a scrolling list that
/// same pull would scroll the list instead.
struct ChapterTimelineView: View {
    let model: ChapterTimelineModel
    let playheadMs: Int64?
    let bookDurationMs: Int64
    let chapterCount: Int

    @State private var lastDragX: CGFloat?
    @State private var dragStartY: CGFloat = 0
    @State private var lastScale: CGFloat = 1

    private static let laneHeight: CGFloat = 96

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            header(
                String(localized: "chapter_editor.whole_book"),
                detail: ChapterTimeFormat.shared.clock(ms: bookDurationMs)
            )
            miniMap
            HStack(spacing: 8) {
                header(
                    String(localized: "chapter_editor.detail_lane"),
                    detail: "\(ChapterTimeFormat.shared.clock(ms: model.windowStartMs)) – "
                        + ChapterTimeFormat.shared.clock(ms: model.windowEndMs)
                )
                Spacer(minLength: 0)
                zoomButton(
                    symbol: "minus.magnifyingglass",
                    label: String(localized: "chapter_editor.zoom_out"),
                    factor: ChapterTimelineModel.zoomOutStep
                )
                zoomButton(
                    symbol: "plus.magnifyingglass",
                    label: String(localized: "chapter_editor.zoom_in"),
                    factor: ChapterTimelineModel.zoomInStep
                )
            }
            lane
            Text(String(localized: "chapter_editor.zoom_hint"))
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
    }

    // MARK: - Minimap

    private var miniMap: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .leading) {
                HStack(spacing: 1) {
                    ForEach(Array(model.density.enumerated()), id: \.offset) { _, weight in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(Color.secondary.opacity(0.12 + 0.88 * weight))
                    }
                }
                RoundedRectangle(cornerRadius: 6)
                    .strokeBorder(Color.listenUpOrange, lineWidth: 2)
                    .background(Color.listenUpOrange.opacity(0.15), in: RoundedRectangle(cornerRadius: 6))
                    .frame(width: max(6, (model.viewportEnd - model.viewportStart) * width))
                    .offset(x: model.viewportStart * width)
                    .allowsHitTesting(false)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { value in
                    guard width > 0 else { return }
                    model.centre(onFraction: value.location.x / width)
                }
            )
        }
        .frame(height: 28)
        .accessibilityElement()
        .accessibilityLabel(String(localized: "chapter_editor.minimap_description"))
    }

    // MARK: - Detail lane

    private var lane: some View {
        GeometryReader { geo in
            let width = geo.size.width
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 14).fill(Color(.tertiarySystemGroupedBackground))
                ForEach(Array(model.fileFractions.enumerated()), id: \.offset) { _, fraction in
                    Rectangle()
                        .fill(Color.secondary.opacity(0.4))
                        .frame(width: 1)
                        .offset(x: fraction * width)
                }
                ForEach(Array(model.ghostFractions.enumerated()), id: \.offset) { _, fraction in
                    Rectangle()
                        .fill(Color.listenUpOrange.opacity(0.45))
                        .frame(width: 2)
                        .offset(x: fraction * width - 1)
                }
                ForEach(model.markers) { marker in
                    markerView(marker).offset(x: marker.fraction * width - 1)
                }
                if let at = playheadMs {
                    Rectangle()
                        .fill(Color.primary)
                        .frame(width: 2)
                        .offset(x: model.fraction(of: at) * width - 1)
                }
                if let readout = model.readout {
                    Text(readout)
                        .font(.caption.weight(.bold).monospacedDigit())
                        .foregroundStyle(.white)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color.listenUpOrange, in: RoundedRectangle(cornerRadius: 8))
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .padding(8)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 14))
            .contentShape(Rectangle())
            .onAppear {
                model.measure(width: width)
                // Open where the listener is: the interesting boundary is the one being heard.
                if let at = playheadMs { model.centre(onMs: at) }
            }
            .onChange(of: width) { _, newWidth in model.measure(width: newWidth) }
            .gesture(laneDrag)
            .simultaneousGesture(lanePinch)
            .haptic(.thresholdActivate, trigger: model.pickups)
            .haptic(.selectionTick, trigger: model.resists)
        }
        .frame(height: Self.laneHeight)
        .accessibilityElement()
        .accessibilityLabel(String(format: String(localized: "chapter_editor.lane_description"), Int32(chapterCount)))
    }

    private func markerView(_ marker: LaneMarker) -> some View {
        let tint: Color = marker.isSelected ? .listenUpOrange : (marker.isLocked ? .secondary : .primary)
        return ZStack(alignment: .topLeading) {
            Rectangle()
                .fill(tint.opacity(marker.isLocked ? 0.5 : 0.85))
                .frame(width: marker.isSelected ? 3 : 2)
            Text("\(marker.number)")
                .font(.caption2.weight(.bold).monospacedDigit())
                .foregroundStyle(tint)
                .offset(x: 5, y: 4)
                .fixedSize()
        }
    }

    /// A press grabs the boundary under it (or pans open lane); movement folds in through the shared
    /// lane; lifting commits once.
    private var laneDrag: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if lastDragX == nil {
                    lastDragX = value.startLocation.x
                    dragStartY = value.startLocation.y
                    model.press(atX: value.startLocation.x)
                }
                let dx = value.location.x - (lastDragX ?? value.location.x)
                lastDragX = value.location.x
                model.move(dx: dx, pulled: value.location.y - dragStartY)
            }
            .onEnded { _ in
                model.release()
                lastDragX = nil
            }
    }

    private var lanePinch: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                model.pinch(scale: value.magnification / lastScale, atX: value.startLocation.x)
                lastScale = value.magnification
            }
            .onEnded { _ in lastScale = 1 }
    }

    // MARK: - Pieces

    private func header(_ title: String, detail: String) -> some View {
        HStack(spacing: 8) {
            Text(title).font(.subheadline.weight(.semibold))
            Text(detail)
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    private func zoomButton(symbol: String, label: String, factor: Float) -> some View {
        Button { model.zoom(by: factor) } label: {
            Image(systemName: symbol).frame(width: 30, height: 30)
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(label)
    }
}
