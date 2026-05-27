import SwiftUI

/// Sheet for selecting a dial-code country. Searchable by name or dial code.
/// Binds the picked country back to the caller so the phone-entry pill updates
/// immediately. Kept deliberately simple — a single `List` + `.searchable` —
/// because the list is short enough that fancy sectioning isn't worth the code.
struct CountryCodePickerSheet: View {
    @Binding var selected: Country
    @Binding var isPresented: Bool
    @State private var query = ""

    private var filtered: [Country] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        if trimmed.isEmpty { return Country.all }
        let q = trimmed.lowercased()
        return Country.all.filter {
            $0.name.lowercased().contains(q) ||
            $0.dialCode.contains(trimmed) ||
            $0.iso.lowercased().contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { country in
                Button {
                    selected = country
                    isPresented = false
                } label: {
                    HStack(spacing: 12) {
                        Text(country.flag)
                            .font(.title3)
                        Text(country.name)
                            .foregroundColor(.keTextPrimary)
                        Spacer()
                        Text(country.dialCode)
                            .foregroundColor(.keTextSecondary)
                            .monospacedDigit()
                        if country.iso == selected.iso {
                            Image(systemName: "checkmark")
                                .foregroundColor(.kePrimary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .listRowBackground(Color.keCard)
            }
            .scrollContentBackground(.hidden)
            .background(Color.keBackground.ignoresSafeArea())
            .searchable(text: $query, prompt: "Search country or code")
            .navigationTitle("Country")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { isPresented = false }
                        .foregroundColor(.kePrimary)
                }
            }
        }
    }
}
