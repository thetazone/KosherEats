import SwiftUI

/// Sheet that lets a multi-restaurant seller choose which of their
/// restaurants is currently active. Choice is persisted in
/// SelectedRestaurant.shared so it survives app restarts.
///
/// Called from the dashboard title when the seller owns more than one
/// restaurant. Single-restaurant sellers never see this.
struct RestaurantPickerSheet: View {
    @Binding var isPresented: Bool
    @Binding var currentID: String?
    let onChange: () -> Void

    @State private var restaurants: [Restaurant] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if isLoading {
                    ProgressView().tint(.kePrimary)
                } else if let err = errorMessage {
                    ErrorStateView(
                        message: err,
                        onRetry: { Task { await load() } },
                    )
                } else {
                    List {
                        ForEach(restaurants) { rest in
                            Button {
                                SelectedRestaurant.shared.set(rest.id)
                                currentID = rest.id
                                Haptics.impact(.light)
                                onChange()
                                isPresented = false
                            } label: {
                                row(for: rest)
                            }
                            .listRowBackground(Color.keCard)
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            }
            .navigationTitle("Your Restaurants")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Done") { isPresented = false }
                        .foregroundColor(.kePrimary)
                }
            }
        }
        .task { await load() }
    }

    private func row(for rest: Restaurant) -> some View {
        HStack(spacing: 12) {
            RemoteImage(url: rest.imageUrl)
                .frame(width: 50, height: 50)
                .cornerRadius(8)

            VStack(alignment: .leading, spacing: 4) {
                Text(rest.name)
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Text(rest.isOpen ? "Open" : "Closed")
                    .font(.caption)
                    .foregroundColor(rest.isOpen ? .keSuccess : .keTextMuted)
            }

            Spacer()

            if currentID == rest.id {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(.kePrimary)
            }
        }
        .padding(.vertical, 6)
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            restaurants = try await APIService.shared.listRestaurants()
            // If the seller only has 1 restaurant we don't need to select
            // anything explicitly — the backend picks it. But if >1 and
            // nothing is set, default to the first one.
            if currentID == nil, let first = restaurants.first {
                SelectedRestaurant.shared.set(first.id)
                currentID = first.id
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
