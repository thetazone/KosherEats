import SwiftUI

struct CourierProfileView: View {
    @EnvironmentObject var auth: AuthViewModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.spacingLG) {
                    if let p = auth.profile {
                        statsHeader(profile: p)
                        vehicleCard(profile: p)
                        payoutRow(profile: p)
                    }

                    Button("Log out") { auth.logout() }
                        .buttonStyle(KESecondaryButtonStyle())
                }
                .padding(Theme.spacingMD)
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("Profile")
            .task { await auth.loadProfile() }
        }
    }

    private func statsHeader(profile: CourierProfile) -> some View {
        HStack(spacing: Theme.spacingLG) {
            statBlock(value: "\(profile.totalDeliveries)", label: "Deliveries")
            Divider().frame(height: 40).background(Color.keDivider)
            statBlock(value: String(format: "%.1f", profile.rating), label: "Rating")
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func statBlock(value: String, label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.bold()).foregroundColor(.kePrimary)
            Text(label).font(.caption).foregroundColor(.keTextTertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private func vehicleCard(profile: CourierProfile) -> some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            Text("Vehicle")
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            row("Type", profile.vehicleType.capitalized)
            if !profile.vehicleMake.isEmpty {
                row("Make / model", "\(profile.vehicleMake) \(profile.vehicleModel)")
            }
            if !profile.licensePlate.isEmpty {
                row("Plate", profile.licensePlate)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(.keTextTertiary)
            Spacer()
            Text(value).foregroundColor(.keTextPrimary)
        }
        .font(.subheadline)
    }

    // Payout row deep-links into the Stripe Connect onboarding flow.
    // Shows green "Active" if already set up, orange "Set up" otherwise.
    private func payoutRow(profile: CourierProfile) -> some View {
        NavigationLink(destination: PayoutsSetupView()) {
            HStack {
                Image(systemName: "dollarsign.circle.fill")
                    .foregroundColor(.kePrimary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Direct deposit")
                        .foregroundColor(.keTextPrimary)
                    Text(profile.payoutReady ? "Active" : "Set up to get paid")
                        .font(.caption)
                        .foregroundColor(profile.payoutReady ? .keSuccess : .kePrimary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .foregroundColor(.keTextTertiary)
            }
            .padding()
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
        }
    }
}
