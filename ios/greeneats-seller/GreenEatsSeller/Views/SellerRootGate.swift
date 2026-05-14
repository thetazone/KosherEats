import SwiftUI

// Sits between SellerApp's auth check and MainTabView. After sign-in we ask
// the backend for the seller's restaurant list — if it's empty, we route to
// SellerOnboardingFlow before letting them touch the dashboard (which would
// otherwise 404 on every endpoint with "restaurant not found").
//
// The 5-step onboarding wizard handles restaurant creation + menu items
// inline (see SellerOnboardingFlow), so we no longer route through a
// separate post-create menu builder.
struct SellerRootGate: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var phase: Phase = .loading

    enum Phase: Equatable {
        case loading
        case empty
        case complete
        case has
    }

    var body: some View {
        Group {
            switch phase {
            case .loading:
                ZStack {
                    Color.keBackground.ignoresSafeArea()
                    ProgressView().tint(.kePrimary)
                }
            case .empty:
                SellerOnboardingFlow { _ in
                    SelectedRestaurant.shared.id = nil
                    phase = .complete
                }
            case .complete:
                OnboardingCompleteView {
                    phase = .has
                }
            case .has:
                MainTabView()
            }
        }
        .task(id: authVM.isAuthenticated) {
            await refresh()
        }
    }

    private func refresh() async {
        guard authVM.isAuthenticated, authVM.hasSellerAccess else { return }
        do {
            let list = try await APIService.shared.listRestaurants()
            phase = list.isEmpty ? .empty : .has
        } catch {
            phase = .has
        }
    }
}
