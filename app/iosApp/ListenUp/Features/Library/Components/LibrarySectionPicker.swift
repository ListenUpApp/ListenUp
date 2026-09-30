import SwiftUI

/// The compact Library's section switcher: Books, Series, Authors, Narrators.
///
/// It is the first row of each section's scroll view and scrolls away with the list, as Music's
/// Library does. It was a pinned top bar first, but on iOS 26.5 that bar's scroll edge effect was
/// drawn over the large title and blurred "Library" (Pass 7 Simulator matrix); in the navigation
/// bar's large-subtitle slot it stopped answering taps over the Authors `List`.
///
/// Text-only segments with noun labels (HIG, Segmented controls: "prefer using either text or
/// images — not a mix of both"; "use nouns or noun phrases for segment labels"). Four segments sit
/// within the HIG's "no more than about five segments on iPhone".
struct LibrarySectionPicker: View {
    @Binding var selection: LibraryTab

    var body: some View {
        Picker(String(localized: "common.library"), selection: $selection) {
            ForEach(LibraryTab.allCases) { section in
                Text(section.title).tag(section)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .haptic(.selectionTick, trigger: selection)
    }

    /// The picker as the first row above a section's content, inset to that content's margins.
    func headerRow(horizontalMargin: CGFloat) -> some View {
        padding(.horizontal, horizontalMargin)
            .padding(.bottom, Spacing.s)
    }
}

/// A section's loading, empty or error state beneath the picker, in a scroll view like the
/// section's content — so switching away from an empty section is always one tap.
struct LibrarySectionState<Content: View>: View {
    let picker: LibrarySectionPicker?
    @ViewBuilder let content: Content

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                picker?.headerRow(horizontalMargin: Spacing.m)
                content
                    .frame(maxWidth: .infinity, minHeight: 360)
            }
        }
        .scrollContentBackground(.hidden)
    }
}
