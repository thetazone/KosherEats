import SwiftUI

struct CourierProfileView: View {
    @EnvironmentObject var auth: AuthViewModel
    @Environment(\.openURL) private var openURL
    @State private var showDeleteConfirm = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: Theme.spacingLG) {
                    if let p = auth.profile {
                        statsHeader(profile: p)
                        vehicleCard(profile: p)
                        payoutRow(profile: p)
                    }

                    // Legal — App Store Review requires in-app links to the
                    // privacy policy and terms (guideline 5.1.1). Opened in
                    // Safari so we avoid hosting an in-app webview.
                    legalCard

                    Button("Log out") { auth.logout() }
                        .buttonStyle(KESecondaryButtonStyle())

                    // Account deletion is mandatory for new apps that allow
                    // account creation (guideline 5.1.1(v)). Cascade to
                    // courier_profiles is handled by the ON DELETE CASCADE FK
                    // on user_id in the schema.
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        HStack {
                            Image(systemName: "trash")
                            Text("Delete account")
                        }
                        .font(.subheadline.bold())
                        .foregroundColor(.keError)
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Color.keError.opacity(0.1))
                        .cornerRadius(Theme.cornerRadiusMedium)
                    }
                }
                .padding(Theme.spacingMD)
            }
            .background(Color.keBackground.ignoresSafeArea())
            .navigationTitle("Profile")
            .task { await auth.loadProfile() }
            .alert("Delete account", isPresented: $showDeleteConfirm) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) {
                    Task { await auth.deleteAccount() }
                }
            } message: {
                Text("This will permanently delete your courier account, profile, and uploaded documents. This action cannot be undone.")
            }
        }
    }

    private var legalCard: some View {
        VStack(spacing: 0) {
            legalRow("Privacy Policy", icon: "shield.fill") {
                openURL(LegalURLs.privacyPolicy)
            }
            Divider().background(Color.keDivider)
            legalRow("Terms of Service", icon: "doc.text.fill") {
                openURL(LegalURLs.termsOfService)
            }
            Divider().background(Color.keDivider)
            legalRow("Help & Support", icon: "questionmark.circle.fill") {
                openURL(LegalURLs.supportEmail)
            }
        }
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }

    private func legalRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundColor(.keTextTertiary)
                    .frame(width: 24)
                Text(title)
                    .font(.system(size: 15))
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Image(systemName: "arrow.up.right.square")
                    .font(.system(size: 13))
                    .foregroundColor(.keTextTertiary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
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
