import SwiftUI

/// A horizontally scrolling row of single-select capsule pills, used to choose an Audible
/// storefront region. The selected pill fills coral with a leading checkmark; the rest read
/// neutral. Generic over any `Hashable` option that maps to a display label.
///
/// On a narrow width the row scrolls; on a wide width (iPad) the caller can drop it into a
/// `FlowLayout` instead — but the scrolling form already stays correct at every size, so this
/// is the default. Selection is communicated via `onSelect`.
struct RegionPicker<Option: Hashable>: View {
    let options: [Option]
    let selection: Option
    let label: (Option) -> String
    let onSelect: (Option) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(options, id: \.self) { option in
                    pill(option)
                }
            }
            .padding(.horizontal, 1)
        }
        .scrollClipDisabled()
    }

    private func pill(_ option: Option) -> some View {
        let isOn = option == selection
        return Button { onSelect(option) } label: {
            HStack(spacing: 6) {
                if isOn {
                    Image(systemName: "checkmark").font(.subheadline.weight(.bold))
                }
                Text(label(option)).font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(isOn ? Color.luOnTint : Color.primary)
            .padding(.horizontal, isOn ? 14 : 16)
            .frame(height: 36)
            .background(Capsule().fill(isOn ? AnyShapeStyle(Color.luTint) : AnyShapeStyle(Color.luFill)))
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }
}

#Preview("RegionPicker") {
    struct Demo: View {
        let regions = ["United States", "United Kingdom", "Germany", "France", "Australia"]
        @State private var selected = "United States"
        var body: some View {
            RegionPicker(options: regions, selection: selected, label: { $0 }) { selected = $0 }
                .padding()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .background(Color.luSurface)
        }
    }
    return Demo()
}
