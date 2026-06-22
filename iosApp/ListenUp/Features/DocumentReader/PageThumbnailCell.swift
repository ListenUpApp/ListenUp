import PDFKit
import SwiftUI

/// Reusable page thumbnail cell shared by the grid overlay and the iPad page rail.
/// Renders a PDFKit thumbnail, outlines the current page in coral, and shows the page number.
struct PageThumbnailCell: View {
    let document: PDFDocument
    let index: Int
    let isCurrent: Bool
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 5) {
                thumbnail
                    .overlay(
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .stroke(isCurrent ? Color.listenUpOrange : Color.clear, lineWidth: 2.5)
                    )
                Text("\(index + 1)")
                    .font(.caption2).monospacedDigit()
                    .foregroundStyle(isCurrent ? Color.listenUpOrange : .secondary)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(format: String(localized: "book.reader_scrubber_page_label"), index + 1))
    }

    @ViewBuilder
    private var thumbnail: some View {
        if let page = document.page(at: index) {
            Image(uiImage: page.thumbnail(of: CGSize(width: 160, height: 210), for: .cropBox))
                .resizable().aspectRatio(contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: 4).fill(Color(.secondarySystemFill)).aspectRatio(0.77, contentMode: .fit)
        }
    }
}
