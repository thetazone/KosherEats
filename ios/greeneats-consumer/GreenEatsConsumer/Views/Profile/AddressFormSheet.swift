import SwiftUI
import MapKit
import Contacts

/// Modal form for adding a new delivery address. Shared between the checkout
/// AddressPickerSheet and the profile SavedAddressesView — different entry
/// points, same fields and save flow.
///
/// Typeahead: the street field is an MKLocalSearchCompleter query. Tapping a
/// suggestion runs a full MKLocalSearch to fill city/state/zip and capture
/// lat/lng so delivery-radius math has real coordinates instead of 0,0.
struct AddressFormSheet: View {
    let onSaved: (Address) -> Void
    var onWarning: ((String) -> Void)? = nil
    @Environment(\.dismiss) var dismiss

    @State private var label = "Home"
    @State private var apt = ""
    @State private var city = ""
    @State private var state = ""
    @State private var zip = ""
    @State private var lat: Double = 0
    @State private var lng: Double = 0
    @State private var isDefault = true
    @State private var isSaving = false
    @State private var errorMessage: String?

    // Typeahead state. Street editing drives the completer's query; tapping
    // a suggestion dismisses the list and fills the remaining fields.
    @StateObject private var autocomplete = AddressAutocomplete()
    @State private var street = ""
    @State private var suppressSuggestions = false

    private var formValid: Bool {
        !street.trimmingCharacters(in: .whitespaces).isEmpty
            && !city.trimmingCharacters(in: .whitespaces).isEmpty
            && !state.trimmingCharacters(in: .whitespaces).isEmpty
            && state.count == 2
            && !zip.trimmingCharacters(in: .whitespaces).isEmpty
            && zip.count == 5
            && (lat != 0 && lng != 0)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: Theme.spacingMD) {
                        field("Label (e.g. Home, Work)", text: $label)

                        VStack(alignment: .leading, spacing: 0) {
                            field("Street address", text: $street)
                                .onChange(of: street) { _, newValue in
                                    if suppressSuggestions {
                                        suppressSuggestions = false
                                        return
                                    }
                                    autocomplete.update(query: newValue)
                                }
                            if !autocomplete.suggestions.isEmpty {
                                suggestionsList
                            }
                        }

                        field("Apt / Suite (optional)", text: $apt)
                        field("City", text: $city)
                        HStack(spacing: 12) {
                            field("State", text: $state)
                                .frame(maxWidth: 100)
                                .textInputAutocapitalization(.characters)
                                .onChange(of: state) { _, val in
                                    state = String(val.filter(\.isLetter).prefix(2))
                                }
                            field("Zip", text: $zip)
                                .keyboardType(.numberPad)
                                .onChange(of: zip) { _, val in
                                    zip = String(val.filter(\.isNumber).prefix(5))
                                }
                        }

                        Toggle("Make this my default", isOn: $isDefault)
                            .tint(.kePrimary)
                            .foregroundColor(.keTextPrimary)

                        if let err = errorMessage {
                            Text(err)
                                .font(.caption)
                                .foregroundColor(.keError)
                        }

                        Button { Task { await save() } } label: {
                            if isSaving { ProgressView().tint(.keTextOnAccent) } else { Text("Save address") }
                        }
                        .buttonStyle(KEPrimaryButtonStyle(isEnabled: formValid && !isSaving))
                        .disabled(!formValid || isSaving)
                        .padding(.top, 8)
                    }
                    .padding()
                }
            }
            .navigationTitle("Add address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
        }
    }

    private var suggestionsList: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(autocomplete.suggestions.prefix(5), id: \.self) { completion in
                Button {
                    Task { await resolve(completion) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(completion.title)
                            .font(.subheadline)
                            .foregroundColor(.keTextPrimary)
                        if !completion.subtitle.isEmpty {
                            Text(completion.subtitle)
                                .font(.caption)
                                .foregroundColor(.keTextTertiary)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 10)
                    .padding(.horizontal, 12)
                }
                Divider().background(Color.keDivider)
            }
        }
        .background(Color.keCard)
        .cornerRadius(8)
        .padding(.top, 4)
    }

    private func field(_ placeholder: String, text: Binding<String>) -> some View {
        TextField(placeholder, text: text)
            .keTextField()
    }

    /// Resolve a completer suggestion into a full street+city+state+zip plus
    /// lat/lng using MKLocalSearch. The completer only returns display
    /// strings; MKLocalSearch gives the underlying MKPlacemark.
    private func resolve(_ completion: MKLocalSearchCompletion) async {
        let request = MKLocalSearch.Request(completion: completion)
        let search = MKLocalSearch(request: request)
        do {
            let response = try await search.start()
            guard let item = response.mapItems.first else { return }
            let placemark = item.placemark
            await MainActor.run {
                // Suppress the next onChange fire so setting `street` doesn't
                // immediately re-trigger the completer with the newly filled text.
                suppressSuggestions = true
                street = [placemark.subThoroughfare, placemark.thoroughfare]
                    .compactMap { $0 }
                    .joined(separator: " ")
                city = placemark.locality ?? ""
                state = placemark.administrativeArea ?? ""
                zip = placemark.postalCode ?? ""
                lat = placemark.coordinate.latitude
                lng = placemark.coordinate.longitude
                autocomplete.clear()
            }
        } catch {
            // Typeahead resolution failing shouldn't block manual entry —
            // the user can keep typing the remaining fields themselves.
        }
    }

    private func save() async {
        isSaving = true
        errorMessage = nil
        defer { isSaving = false }

        let draft = Address(
            id: "", userID: "", label: label, street: street,
            apt: apt.isEmpty ? nil : apt, city: city, state: state, zipCode: zip,
            lat: lat, lng: lng, isDefault: isDefault,
        )
        do {
            var saved = try await APIService.shared.addAddress(draft)
            if isDefault {
                do {
                    // Backend AddAddress doesn't yet flip is_default on existing rows.
                    // Call the dedicated endpoint so other saved addresses lose it.
                    try await APIService.shared.setDefaultAddress(id: saved.id)
                    saved.isDefault = true
                } catch {
                    // The address itself exists, but we couldn't promote it to
                    // the sole default. Report that explicitly after dismiss so
                    // the caller can prompt the user to retry from the list view.
                    saved.isDefault = false
                    onWarning?("Address saved, but it couldn't be made default.")
                }
            }
            onSaved(saved)
            dismiss()
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }
}

/// Thin SwiftUI wrapper around MKLocalSearchCompleter. Publishes the current
/// suggestion list so a view can render it as a typeahead. Filters to address
/// results only (not POIs) because a delivery address has to be a specific
/// street, not "Starbucks".
@MainActor
final class AddressAutocomplete: NSObject, ObservableObject, MKLocalSearchCompleterDelegate {
    @Published var suggestions: [MKLocalSearchCompletion] = []

    private let completer: MKLocalSearchCompleter

    override init() {
        self.completer = MKLocalSearchCompleter()
        super.init()
        completer.resultTypes = .address
        completer.delegate = self
    }

    func update(query: String) {
        guard query.count >= 3 else {
            suggestions = []
            return
        }
        completer.queryFragment = query
    }

    func clear() {
        suggestions = []
        completer.queryFragment = ""
    }

    nonisolated func completerDidUpdateResults(_ completer: MKLocalSearchCompleter) {
        let results = completer.results
        Task { @MainActor in
            self.suggestions = results
        }
    }

    nonisolated func completer(_ completer: MKLocalSearchCompleter, didFailWithError error: Error) {
        Task { @MainActor in
            self.suggestions = []
        }
    }
}
