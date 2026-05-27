import SwiftUI

/// KosherFilterSheet is the differentiator UI — it lets the user narrow
/// restaurants by certification agency, Glatt Kosher, Cholov Yisroel, and
/// Pas Yisroel. This is the product's core value prop: "find kosher food
/// that actually matches YOUR kashrus standards."
///
/// Pattern matches UberEats's filter sheets — buttons toggle, "Show X
/// results" live-updates at the bottom, Apply commits to the shared restaurant store.
struct KosherFilterSheet: View {
    @Binding var isPresented: Bool
    let allRestaurants: [Restaurant]
    let currentFilters: KosherFilters
    let onApply: (KosherFilters) -> Void

    @State private var draft: KosherFilters

    init(
        isPresented: Binding<Bool>,
        allRestaurants: [Restaurant],
        currentFilters: KosherFilters,
        onApply: @escaping (KosherFilters) -> Void,
    ) {
        self._isPresented = isPresented
        self.allRestaurants = allRestaurants
        self.currentFilters = currentFilters
        self.onApply = onApply
        self._draft = State(initialValue: currentFilters)
    }

    /// Live preview of how many restaurants match the *draft* filters.
    /// Recomputes on every toggle so the user can gauge how narrow they're going.
    private var previewCount: Int {
        allRestaurants.filter { r in
            if !draft.certifications.isEmpty && !draft.certifications.contains(r.kosherCertification) {
                return false
            }
            if draft.glattOnly && !r.isGlattKosher { return false }
            if draft.cholovYisroelOnly && !r.isCholovYisroel { return false }
            if draft.pasYisroelOnly && !r.isPasYisroel { return false }
            return true
        }.count
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(alignment: .leading, spacing: Theme.spacingLG) {
                        certificationSection
                        dietarySection
                    }
                    .padding()
                    .padding(.bottom, 120)
                }

                VStack {
                    Spacer()
                    applyBar
                }
            }
            .navigationTitle("Filters")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { isPresented = false }
                        .foregroundColor(.kePrimary)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    if draft.isActive {
                        Button("Clear") {
                            draft = KosherFilters()
                        }
                        .foregroundColor(.keError)
                    }
                }
            }
        }
    }

    // MARK: - Certification section

    private var certificationSection: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            sectionHeader("Certification", subtitle: "Select any that work for you")

            // Grid of 2 columns, all available certifications from the enum.
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                ForEach(KosherCertification.allCases, id: \.self) { cert in
                    CertChip(
                        cert: cert,
                        isSelected: draft.certifications.contains(cert),
                        action: {
                            if draft.certifications.contains(cert) {
                                draft.certifications.remove(cert)
                            } else {
                                draft.certifications.insert(cert)
                            }
                        },
                    )
                }
            }
        }
    }

    // MARK: - Dietary section

    private var dietarySection: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            sectionHeader("Dietary Standards", subtitle: "Stricter kashrus? Toggle what matters to you")

            toggleRow(
                title: "Glatt Kosher",
                subtitle: "Only Glatt-certified meat establishments",
                icon: "checkmark.seal.fill",
                iconColor: .kePrimary,
                isOn: $draft.glattOnly,
            )
            toggleRow(
                title: "Cholov Yisroel",
                subtitle: "Dairy under full Yisroel supervision",
                icon: "drop.fill",
                iconColor: .keDairy,
                isOn: $draft.cholovYisroelOnly,
            )
            toggleRow(
                title: "Pas Yisroel",
                subtitle: "Baked goods under full Yisroel supervision",
                icon: "birthday.cake.fill",
                iconColor: .keWarning,
                isOn: $draft.pasYisroelOnly,
            )
        }
    }

    // MARK: - Apply bar

    private var applyBar: some View {
        VStack(spacing: 0) {
            Divider().background(Color.keDivider)
            Button {
                onApply(draft)
                isPresented = false
            } label: {
                HStack {
                    Text(previewCount == 0 ? String(localized: "No matches") : String(localized: "Show \(previewCount) result\(previewCount == 1 ? "" : "s")"))
                    if draft.activeCount > 0 {
                        Spacer()
                        Text("\(draft.activeCount) filter\(draft.activeCount == 1 ? "" : "s")")
                            .font(.caption.bold())
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.white.opacity(0.25))
                            .cornerRadius(6)
                    }
                }
            }
            .buttonStyle(KEPrimaryButtonStyle(isEnabled: previewCount > 0))
            .disabled(previewCount == 0)
            .padding()
            .background(Color.keBackgroundElevated)
        }
    }

    // MARK: - Helpers

    private func sectionHeader(_ title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title3.bold())
                .foregroundColor(.keTextPrimary)
            Text(subtitle)
                .font(.caption)
                .foregroundColor(.keTextTertiary)
        }
    }

    private func toggleRow(
        title: String,
        subtitle: String,
        icon: String,
        iconColor: Color,
        isOn: Binding<Bool>,
    ) -> some View {
        HStack(spacing: Theme.spacingMD) {
            Image(systemName: icon)
                .foregroundColor(iconColor)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.keTextPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.keTextTertiary)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(.kePrimary)
                .accessibilityLabel(title)
                .accessibilityHint(subtitle)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
    }
}

// MARK: - Cert chip

private struct CertChip: View {
    let cert: KosherCertification
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isSelected ? .kePrimary : .keTextMuted)
                    .accessibilityHidden(true)
                Text(cert.displayName)
                    .font(.subheadline.bold())
                    .foregroundColor(isSelected ? .keTextPrimary : .keTextSecondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 14)
            .background(
                RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                    .fill(Color.keCard)
                    .overlay(
                        RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                            .stroke(isSelected ? Color.kePrimary : Color.clear, lineWidth: 2),
                    ),
            )
        }
        .accessibilityLabel("\(cert.displayName) certification")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
