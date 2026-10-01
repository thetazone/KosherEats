import SwiftUI

// Sits between SellerApp's auth check and MainTabView. After sign-in we ask
// the backend for the seller's restaurant list — if it's empty, we route to
// SellerOnboardingFlow before letting them touch the dashboard (which would
// otherwise 404 on every endpoint with "restaurant not found").
//
// The 5-step onboarding wizard handles restaurant creation + menu items
// inline (see SellerOnboardingFlow), so we no longer route through a
// separate post-create menu builder.
//
// Restaurant Partner Agreement: once a restaurant exists (on launch / after
// login, and again right after onboarding creates it — i.e. before onboarding
// completes) we GET /seller/agreement. When it's required and the current
// version isn't accepted, the gate swaps in PartnerAgreementView as its own
// phase — full-screen with no dismiss path — until the seller accepts.
// Grandfathered restaurants get required=false and skip straight through.
struct SellerRootGate: View {
    @EnvironmentObject var authVM: AuthViewModel
    @State private var phase: Phase = .loading
    /// The agreement payload that put us into `.agreement`, handed to the
    /// screen so it doesn't re-fetch on appear.
    @State private var pendingAgreement: SellerAgreement?

    enum Phase: Equatable {
        case loading
        case empty
        /// Agreement must be accepted; `then` is where to go afterwards.
        case agreement(then: AfterAgreement)
        case complete
        case has
        case failed
    }

    enum AfterAgreement: Equatable {
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
                    // The restaurant now exists, so the agreement (which is
                    // per restaurant) can be checked before we celebrate.
                    Task { await routeThroughAgreement(then: .complete) }
                }
            case .agreement(let next):
                if let agreement = pendingAgreement {
                    PartnerAgreementView(agreement: agreement) {
                        pendingAgreement = nil
                        phase = next == .complete ? .complete : .has
                    }
                    .environmentObject(authVM)
                } else {
                    // Defensive: never strand the seller on a blank screen.
                    Color.keBackground.ignoresSafeArea()
                        .onAppear { phase = next == .complete ? .complete : .has }
                }
            case .complete:
                OnboardingCompleteView {
                    phase = .has
                }
            case .has:
                MainTabView()
            case .failed:
                ZStack {
                    Color.keBackground.ignoresSafeArea()
                    ErrorStateView(
                        message: "Couldn't load your account. Check your connection and try again.",
                        onRetry: { Task { await refresh() } }
                    )
                }
            }
        }
        .task(id: authVM.isAuthenticated) {
            await refresh()
        }
    }

    private func refresh() async {
        guard authVM.isAuthenticated, authVM.hasSellerAccess else { return }
        phase = .loading
        do {
            let list = try await APIService.shared.listRestaurants()
            if list.isEmpty {
                phase = .empty
            } else {
                await routeThroughAgreement(then: .has)
            }
        } catch {
            // Fail open to the dashboard ONLY if this device has completed
            // onboarding before (a persisted restaurant selection proves it) —
            // the tabs have their own retries. A brand-new seller has no
            // persisted id, so a flaky-network/cold-start error must NOT drop
            // them onto a dashboard that 404s "restaurant not found"
            // everywhere with no path back to onboarding; show retry instead.
            phase = SelectedRestaurant.shared.id != nil ? .has : .failed
        }
    }

    /// Shows the Partner Agreement when the backend requires it, otherwise
    /// continues to `next`.
    ///
    /// Fails OPEN on a fetch error (network blip, or a backend that doesn't
    /// serve /seller/agreement yet): an outage here must not lock every
    /// restaurant out of their live orders. The backend remains the
    /// enforcement point for anything that actually needs acceptance, and the
    /// check re-runs on the next launch / login.
    private func routeThroughAgreement(then next: AfterAgreement) async {
        phase = .loading
        if let agreement = try? await APIService.shared.getAgreement(), agreement.needsAcceptance {
            pendingAgreement = agreement
            phase = .agreement(then: next)
        } else {
            pendingAgreement = nil
            phase = next == .complete ? .complete : .has
        }
    }
}
