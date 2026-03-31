import SwiftUI

struct MenuItemFormView: View {
    let categories: [MenuCategory]
    var existingItem: MenuItem?
    let onSave: (String, String, String, Double, Bool, Bool, Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var description = ""
    @State private var priceText = ""
    @State private var selectedCategoryId = ""
    @State private var isMeat = false
    @State private var isDairy = false
    @State private var isPareve = true

    var isEditing: Bool { existingItem != nil }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Basic Info
                        formSection("Basic Info") {
                            formField("Item Name", text: $name, placeholder: "e.g., Falafel Plate")

                            formField("Description", text: $description, placeholder: "Describe this dish...")

                            VStack(alignment: .leading, spacing: 8) {
                                Text("Price")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)

                                HStack {
                                    Text("$")
                                        .foregroundColor(.keTextMuted)
                                    TextField("0.00", text: $priceText)
                                        .keyboardType(.decimalPad)
                                        .foregroundColor(.keTextPrimary)
                                }
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(12)
                            }
                        }

                        // Category
                        formSection("Category") {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Category")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(categories) { cat in
                                            Button {
                                                selectedCategoryId = cat.id
                                            } label: {
                                                Text(cat.name)
                                                    .font(.subheadline)
                                                    .foregroundColor(
                                                        selectedCategoryId == cat.id ? .white : .keTextSecondary
                                                    )
                                                    .padding(.horizontal, 16)
                                                    .padding(.vertical, 10)
                                                    .background(
                                                        selectedCategoryId == cat.id ? Color.kePrimary : Color.keCard
                                                    )
                                                    .cornerRadius(10)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Kosher Classification
                        formSection("Kosher Classification") {
                            VStack(spacing: 12) {
                                kosherToggle("Meat", icon: "flame.fill", color: .keError, isOn: $isMeat) {
                                    isDairy = false
                                    isPareve = false
                                }

                                kosherToggle("Dairy", icon: "drop.fill", color: .blue, isOn: $isDairy) {
                                    isMeat = false
                                    isPareve = false
                                }

                                kosherToggle("Pareve", icon: "leaf.fill", color: .keSuccess, isOn: $isPareve) {
                                    isMeat = false
                                    isDairy = false
                                }
                            }
                        }

                        // Save Button
                        Button {
                            let price = Double(priceText) ?? 0
                            onSave(selectedCategoryId, name, description, price, isMeat, isDairy, isPareve)
                        } label: {
                            Text(isEditing ? "Update Item" : "Add Item")
                                .font(.headline)
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(canSave ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                                .cornerRadius(14)
                        }
                        .disabled(!canSave)
                    }
                    .padding()
                }
            }
            .navigationTitle(isEditing ? "Edit Item" : "New Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .foregroundColor(.kePrimary)
                }
            }
            .onAppear {
                if let item = existingItem {
                    name = item.name
                    description = item.description
                    priceText = String(format: "%.2f", item.price)
                    selectedCategoryId = item.categoryId
                    isMeat = item.isMeat
                    isDairy = item.isDairy
                    isPareve = item.isPareve
                } else if let first = categories.first {
                    selectedCategoryId = first.id
                }
            }
        }
    }

    // MARK: - Form Helpers

    private func formSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
                .foregroundColor(.keTextPrimary)

            content()
        }
    }

    private func formField(_ label: String, text: Binding<String>, placeholder: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            TextField(placeholder, text: text)
                .foregroundColor(.keTextPrimary)
                .padding()
                .background(Color.keCard)
                .cornerRadius(12)
        }
    }

    private func kosherToggle(
        _ label: String,
        icon: String,
        color: Color,
        isOn: Binding<Bool>,
        exclusiveAction: @escaping () -> Void
    ) -> some View {
        Button {
            exclusiveAction()
            isOn.wrappedValue = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundColor(color)
                    .frame(width: 24)

                Text(label)
                    .font(.subheadline)
                    .foregroundColor(.keTextPrimary)

                Spacer()

                Image(systemName: isOn.wrappedValue ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isOn.wrappedValue ? color : .keTextMuted)
                    .font(.title3)
            }
            .padding()
            .background(isOn.wrappedValue ? color.opacity(0.1) : Color.keCard)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isOn.wrappedValue ? color.opacity(0.3) : Color.clear, lineWidth: 1)
            )
        }
    }

    private var canSave: Bool {
        !name.isEmpty &&
        !selectedCategoryId.isEmpty &&
        (Double(priceText) ?? 0) > 0 &&
        (isMeat || isDairy || isPareve)
    }
}
