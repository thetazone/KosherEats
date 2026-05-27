import SwiftUI

struct KosherCertificateSheet: View {
    let url: String
    let restaurantName: String
    @Environment(\.dismiss) private var dismiss
    @State private var loadFailed = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if let imageURL = URL(string: url) {
                    ScrollView {
                        AsyncImage(url: imageURL) { phase in
                            switch phase {
                            case .success(let image):
                                image
                                    .resizable()
                                    .scaledToFit()
                                    .cornerRadius(12)
                                    .padding()
                                    .accessibilityLabel("Kosher certificate for \(restaurantName)")
                            case .failure:
                                errorView
                                    .onAppear { loadFailed = true }
                            case .empty:
                                ProgressView("Loading certificate…")
                                    .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                                    .frame(maxWidth: .infinity, minHeight: 300)
                                    .accessibilityLabel("Loading kosher certificate")
                            @unknown default:
                                EmptyView()
                            }
                        }
                    }
                } else {
                    // URL string was malformed — can't even attempt a load.
                    errorView
                }
            }
            .navigationTitle("Kosher Certificate")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityHint("Dismiss certificate view")
                }
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var errorView: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
            Text("Unable to load certificate")
                .foregroundColor(.keTextSecondary)
            Text("The certificate image for \(restaurantName) could not be loaded. Please try again later.")
                .font(.caption)
                .foregroundColor(.keTextMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, minHeight: 300)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Unable to load kosher certificate for \(restaurantName)")
    }
}
