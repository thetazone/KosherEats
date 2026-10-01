import SwiftUI

/// Full-screen, non-dismissable Restaurant Partner Agreement gate for new
/// restaurants. SellerRootGate swaps this in (instead of presenting a sheet)
/// whenever GET /seller/agreement says `required` and the current version
/// hasn't been accepted, so there is no swipe-down / Cancel path around it —
/// the only ways out are Accept or Log out.
///
/// Grandfathered restaurants get required=false from the backend and never
/// see this screen.
struct PartnerAgreementView: View {
    @EnvironmentObject var authVM: AuthViewModel

    @State private var agreement: SellerAgreement
    let onAccepted: () -> Void

    @State private var legalName = ""
    @State private var agreed = false
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var versionNotice: String?
    @State private var safari: SafariDestination?
    @State private var showLogoutConfirm = false
    @FocusState private var nameFocused: Bool

    init(agreement: SellerAgreement, onAccepted: @escaping () -> Void) {
        _agreement = State(initialValue: agreement)
        self.onAccepted = onAccepted
    }

    private var trimmedName: String {
        legalName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canAccept: Bool {
        !trimmedName.isEmpty && agreed && !isSubmitting && !agreement.currentVersion.isEmpty
    }

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    header

                    if let notice = versionNotice {
                        noticeBanner(notice)
                    }

                    keyTerms
                    readFullAgreementButton
                    legalNameField
                    agreeCheckbox

                    if let err = errorMessage {
                        Text(err)
                            .font(.caption)
                            .foregroundColor(.keError)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    acceptButton

                    Button {
                        showLogoutConfirm = true
                    } label: {
                        Text("Not ready? Log out")
                            .font(.subheadline)
                            .foregroundColor(.keTextMuted)
                            .frame(maxWidth: .infinity)
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }
                .padding()
                .padding(.top, 8)
                .adaptiveContentWidth(600)
            }
            .scrollDismissesKeyboard(.interactively)
            // No nav bar here, so paint the status-bar area: a zero-height
            // inset whose background bleeds into the top safe area keeps
            // scrolled text from colliding with the clock / Dynamic Island.
            .safeAreaInset(edge: .top, spacing: 0) {
                Color.clear.frame(height: 0).background(Color.keBackground)
            }
        }
        .interactiveDismissDisabled(true)
        .sheet(item: $safari) { destination in
            InAppSafariView(url: destination.url)
                .ignoresSafeArea()
        }
        .alert("Log Out", isPresented: $showLogoutConfirm) {
            Button("Cancel", role: .cancel) {}
            Button("Log Out", role: .destructive) { authVM.logout() }
        } message: {
            Text("You'll need to accept the Restaurant Partner Agreement next time you sign in.")
        }
    }

    // MARK: - Sections

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            ZStack {
                Circle()
                    .fill(Color.kePrimary.opacity(0.15))
                    .frame(width: 64, height: 64)
                Image(systemName: "signature")
                    .font(.system(size: 28, weight: .semibold))
                    .foregroundColor(.kePrimary)
            }
            .accessibilityHidden(true)

            Text("Restaurant Partner Agreement")
                .font(.system(size: 28, weight: .bold))
                .foregroundColor(.keTextPrimary)
                .accessibilityAddTraits(.isHeader)

            Text("Please review the key terms and accept on behalf of your business before you start taking orders.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
                .fixedSize(horizontal: false, vertical: true)

            if !agreement.currentVersion.isEmpty {
                Text("Version \(agreement.currentVersion)")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(.keTextMuted)
            }
        }
    }

    private var keyTerms: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Key terms")
                .font(.headline)
                .foregroundColor(.keTextPrimary)

            termRow(icon: "percent", title: "Fees",
                    text: "KosherEats keeps 10% of the food subtotal on orders delivered by our courier partners, and 5% plus card processing on pickup and self-delivered orders.")
            termRow(icon: "building.columns.fill", title: "Payouts",
                    text: "Payouts are sent to your bank account through Stripe.")
            termRow(icon: "doc.plaintext.fill", title: "Sales tax",
                    text: "You receive 100% of the sales tax collected on your orders — you're responsible for reporting and remitting it.")
            termRow(icon: "figure.walk", title: "Delivery workers",
                    text: "You allow delivery workers to use your restroom when they're picking up orders.")
            termRow(icon: "checkmark.seal.fill", title: "Your menu",
                    text: "You're responsible for the accuracy of your menu, pricing, and kosher certification.")
            termRow(icon: "arrow.uturn.left.circle.fill", title: "Ending the agreement",
                    text: "Either party may end the agreement at any time.")
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(16)
    }

    private func termRow(icon: String, title: String, text: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.subheadline)
                .foregroundColor(.kePrimary)
                .frame(width: 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.keTextPrimary)
                Text(text)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var readFullAgreementButton: some View {
        Button {
            safari = SafariDestination(url: agreement.termsURL)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "doc.text.magnifyingglass")
                Text("Read the full agreement")
                Spacer()
                Image(systemName: "arrow.up.right.square")
                    .font(.footnote)
            }
            .font(.subheadline.bold())
            .foregroundColor(.kePrimary)
            .padding(.horizontal, 16)
            .frame(height: 48)
            .background(Color.kePrimary.opacity(0.12))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens the full Restaurant Partner Agreement")
    }

    private var legalNameField: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Legal business name")
                .font(.caption)
                .foregroundColor(.keTextSecondary)
            TextField("", text: $legalName, prompt: Text("e.g. Kosher Bites LLC").foregroundColor(.keTextMuted))
                .textContentType(.organizationName)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.done)
                .onSubmit { nameFocused = false }
                .focused($nameFocused)
                .foregroundColor(.keTextPrimary)
                .padding()
                .background(Color.keCard)
                .cornerRadius(10)
                .overlay(
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(nameFocused ? Color.kePrimary : Color.keBorder, lineWidth: 1)
                )
                .accessibilityLabel("Legal business name")
        }
    }

    private var agreeCheckbox: some View {
        Button {
            Haptics.impact(.light)
            agreed.toggle()
        } label: {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: agreed ? "checkmark.square.fill" : "square")
                    .font(.title3)
                    .foregroundColor(agreed ? .kePrimary : .keTextTertiary)
                Text("I have read and agree to the Restaurant Partner Agreement on behalf of this business")
                    .font(.subheadline)
                    .foregroundColor(.keTextPrimary)
                    .multilineTextAlignment(.leading)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("I have read and agree to the Restaurant Partner Agreement on behalf of this business")
        .accessibilityValue(agreed ? "Checked" : "Unchecked")
        .accessibilityAddTraits(.isButton)
    }

    private var acceptButton: some View {
        Button {
            nameFocused = false
            Task { await accept() }
        } label: {
            Group {
                if isSubmitting {
                    ProgressView().tint(.keTextOnAccent)
                } else {
                    Text("Accept")
                        .font(.headline)
                }
            }
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(canAccept || isSubmitting ? Color.kePrimary : Color.kePrimary.opacity(0.4))
            .cornerRadius(14)
        }
        .disabled(!canAccept)
        .accessibilityHint(canAccept ? "" : "Enter your legal business name and check the box to continue")
    }

    private func noticeBanner(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.keWarning)
                .accessibilityHidden(true)
            Text(text)
                .font(.subheadline)
                .foregroundColor(.keTextPrimary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.keWarning.opacity(0.12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(Color.keWarning.opacity(0.4), lineWidth: 1)
        )
        .cornerRadius(12)
    }

    // MARK: - Actions

    private func accept() async {
        guard canAccept else { return }
        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        do {
            let result = try await APIService.shared.acceptAgreement(
                legalName: trimmedName,
                version: agreement.currentVersion
            )
            if result.needsAcceptance {
                // Accepted, but the server already moved to a newer version.
                showNewVersion(result)
            } else {
                Haptics.notify(.success)
                onAccepted()
            }
        } catch APIError.serverError(let code, _) where code == 409 {
            // The terms changed while this screen was open — fetch the new
            // version and make the seller re-confirm it.
            await refetchAfterConflict()
        } catch {
            Haptics.notify(.error)
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func refetchAfterConflict() async {
        do {
            let fresh = try await APIService.shared.getAgreement()
            if fresh.needsAcceptance {
                showNewVersion(fresh)
            } else {
                // Already accepted (e.g. a co-owner on another device).
                onAccepted()
            }
        } catch {
            errorMessage = "The agreement was updated. Couldn't load the new version — check your connection and try again."
        }
    }

    private func showNewVersion(_ fresh: SellerAgreement) {
        agreement = fresh
        agreed = false
        Haptics.notify(.warning)
        let version = fresh.currentVersion.isEmpty ? "" : " (version \(fresh.currentVersion))"
        versionNotice = "The Restaurant Partner Agreement was just updated\(version). Please review the new terms and accept again."
    }
}
