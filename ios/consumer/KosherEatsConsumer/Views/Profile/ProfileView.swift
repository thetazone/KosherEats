import SwiftUI

struct ProfileView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.openURL) private var openURL
    @State private var showEditProfile = false
    @State private var showDeleteConfirmation = false

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(spacing: Theme.spacingLG) {
                        // Avatar + Name
                        profileHeader

                        // Menu items
                        VStack(spacing: 2) {
                            ProfileMenuItem(icon: "person.fill", title: "Edit Profile", color: .kePrimary) {
                                showEditProfile = true
                            }
                            NavigationLink {
                                SavedAddressesView()
                            } label: {
                                ProfileMenuRow(icon: "mappin.circle.fill", title: "Saved Addresses", color: .kePrimary)
                            }
                            NavigationLink {
                                PaymentMethodsView()
                            } label: {
                                ProfileMenuRow(icon: "creditcard.fill", title: "Payment Methods", color: .keSuccess)
                            }
                            NavigationLink {
                                NotificationPreferencesView()
                            } label: {
                                ProfileMenuRow(icon: "bell.fill", title: "Notifications", color: .keWarning)
                            }
                            ProfileMenuItem(icon: "shield.fill", title: "Privacy Policy", color: .keDairy) {
                                openURL(LegalURLs.privacyPolicy)
                            }
                            ProfileMenuItem(icon: "questionmark.circle.fill", title: "Help & Support", color: .keTextSecondary) {
                                openURL(LegalURLs.supportEmail)
                            }
                            ProfileMenuItem(icon: "doc.text.fill", title: "Terms of Service", color: .keTextSecondary) {
                                openURL(LegalURLs.termsOfService)
                            }
                        }
                        .background(Color.keCard)
                        .cornerRadius(Theme.cornerRadiusMedium)
                        .padding(.horizontal)

                        // Logout
                        Button {
                            authVM.logout()
                        } label: {
                            HStack {
                                Image(systemName: "rectangle.portrait.and.arrow.right")
                                Text("Sign Out")
                            }
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(.keError)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(Color.keCard)
                            .cornerRadius(Theme.cornerRadiusMedium)
                        }
                        .padding(.horizontal)

                        // Delete Account
                        Button {
                            showDeleteConfirmation = true
                        } label: {
                            HStack {
                                Image(systemName: "trash")
                                Text("Delete Account")
                            }
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.keTextMuted)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                        }
                        .padding(.horizontal)
                        .alert("Delete Account", isPresented: $showDeleteConfirmation) {
                            Button("Cancel", role: .cancel) {}
                            Button("Delete", role: .destructive) {
                                Task { await authVM.deleteAccount() }
                            }
                        } message: {
                            Text("This will permanently delete your account and all associated data. This action cannot be undone.")
                        }

                        // App version
                        Text("KosherEats v1.0.0")
                            .font(.system(size: 12))
                            .foregroundColor(.keTextMuted)
                            .padding(.bottom, Theme.spacingXL)
                    }
                    .padding(.top)
                }
            }
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.large)
            .sheet(isPresented: $showEditProfile) {
                EditProfileView()
            }
        }
    }

    // MARK: - Profile Header

    private var profileHeader: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(
                        LinearGradient(
                            colors: [.kePrimary, .kePrimaryDark],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: 88, height: 88)

                Text(initials)
                    .font(.system(size: 32, weight: .bold))
                    .foregroundColor(.white)
            }

            if let user = authVM.user {
                Text(user.fullName)
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(.keTextPrimary)

                Text(user.email)
                    .font(.system(size: 15))
                    .foregroundColor(.keTextSecondary)
            }
        }
        .padding(.vertical, Theme.spacingMD)
    }

    private var initials: String {
        guard let user = authVM.user else { return "?" }
        let first = user.firstName.prefix(1)
        let last = user.lastName.prefix(1)
        return "\(first)\(last)".uppercased()
    }
}

// MARK: - Profile Menu Item

struct ProfileMenuItem: View {
    let icon: String
    let title: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ProfileMenuRow(icon: icon, title: title, color: color)
        }
    }
}

/// Presentation-only variant of ProfileMenuItem — use inside NavigationLink
/// so the system supplies the tap behavior.
struct ProfileMenuRow: View {
    let icon: String
    let title: String
    let color: Color

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(color)
                .frame(width: 28)

            Text(title)
                .font(.system(size: 16))
                .foregroundColor(.keTextPrimary)

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(.keTextMuted)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .contentShape(Rectangle())
    }
}

// MARK: - Edit Profile View

struct EditProfileView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.dismiss) var dismiss
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var phone = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: Theme.spacingLG) {
                    VStack(spacing: 14) {
                        FormField(title: "First Name", text: $firstName, placeholder: "First name")
                        FormField(title: "Last Name", text: $lastName, placeholder: "Last name")
                        FormField(title: "Phone", text: $phone, placeholder: "Phone number", keyboard: .phonePad, content: .telephoneNumber)
                    }
                    .padding(.horizontal)
                    .padding(.top, Theme.spacingLG)

                    if let error = authVM.errorMessage {
                        Text(error)
                            .font(.system(size: 14))
                            .foregroundColor(.keError)
                    }

                    Button {
                        Task {
                            await authVM.updateProfile(firstName: firstName, lastName: lastName, phone: phone)
                            if authVM.errorMessage == nil {
                                dismiss()
                            }
                        }
                    } label: {
                        HStack {
                            if authVM.isLoading {
                                ProgressView().tint(.white)
                            } else {
                                Text("Save Changes")
                            }
                        }
                    }
                    .buttonStyle(KEPrimaryButtonStyle(isEnabled: !authVM.isLoading))
                    .disabled(authVM.isLoading)
                    .padding(.horizontal)

                    Spacer()
                }
            }
            .navigationTitle("Edit Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
            .onAppear {
                if let user = authVM.user {
                    firstName = user.firstName
                    lastName = user.lastName
                    phone = user.phone
                }
            }
        }
    }
}
