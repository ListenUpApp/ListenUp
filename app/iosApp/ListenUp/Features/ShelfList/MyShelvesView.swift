import SwiftUI

/// The My Shelves list (spec §3.2 H24), reached from Home's See All: every shelf the reader owns, as Home's shelf
/// tiles in a grid that flows from the width, with New Shelf in the toolbar.
struct MyShelvesView: View {
    @Environment(\.dependencies) private var deps
    @State private var observer: MyShelvesObserver?
    @State private var isCreatingShelf = false

    var body: some View {
        Group {
            switch observer?.phase ?? .loading {
            case .loading:
                LoadingStateView()
            case .error:
                ContentUnavailableView(
                    String(localized: "home.couldnt_load_shelves"),
                    systemImage: "exclamationmark.triangle"
                )
            case .ready(let shelves) where shelves.isEmpty:
                ContentUnavailableView {
                    Label(String(localized: "home.my_shelves_ios"), systemImage: "books.vertical")
                } description: {
                    Text(String(localized: "home.shelves_invitation"))
                }
            case .ready(let shelves):
                grid(shelves)
            }
        }
        .background(Color.luSurface)
        .navigationTitle(String(localized: "home.my_shelves_ios"))
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    isCreatingShelf = true
                } label: {
                    Label(String(localized: "home.new_shelf_ios"), systemImage: "plus")
                }
            }
        }
        // A new shelf is a small self-contained task, so it is a sheet (HIG, Sheets), as from Home.
        .sheet(isPresented: $isCreatingShelf) {
            CreateEditShelfView(shelfId: nil)
        }
        .task {
            if observer == nil { observer = MyShelvesObserver(viewModel: deps.createMyShelvesViewModel()) }
        }
        .onDisappear {
            // Release the observer; its deinit cancels the FlowBridge subscription and clears the ViewModel.
            observer = nil
        }
    }

    private func grid(_ shelves: [ShelfItem]) -> some View {
        ScrollView {
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: ShelfCard.tileWidth), spacing: 14, alignment: .top)],
                alignment: .leading,
                spacing: 20
            ) {
                ForEach(shelves) { shelf in
                    NavigationLink(value: ShelfDestination(id: shelf.id)) {
                        ShelfCard(shelf: shelf, style: .tile)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
    }
}
