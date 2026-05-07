import SwiftUI

struct OnboardingMenuItem: Identifiable {
    let id = UUID()
    var name: String
    var description: String
    var priceDollars: String
    var categoryName: String
    var isMeat: Bool
    var isDairy: Bool
    var isPareve: Bool
}

struct OnboardingMenuBuilderView: View {
    let onComplete: () -> Void
    let onSkip: () -> Void

    @State private var items: [OnboardingMenuItem] = []
    @State private var showForm = false
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private let defaultCategories = [
        "Appetizers", "Soups", "Salads", "Mains",
        "Sides", "Desserts", "Drinks",
    ]

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 20) {
                    header

                    ForEach(items) { item in
                        menuItemCard(item)
                    }

                    if showForm {
                        AddItemForm(
                            categories: defaultCategories,
                            onAdd: { item in
                                items.append(item)
                                showForm = false
                            },
                            onCancel: { showForm = false }
                        )
                    } else {
                        Button { showForm = true } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "plus.circle.fill")
                                Text("Add Menu Item")
                            }
                            .font(.subheadline.bold())
                            .foregroundColor(.kePrimary)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(Color.kePrimary.opacity(0.1))
                            .cornerRadius(12)
                        }
                    }

                    if let error = errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.keError)
                    }

                    submitButton

                    Button("Skip for now — add menu items later") {
                        onSkip()
                    }
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 4)

                    Spacer().frame(height: 40)
                }
                .padding(20)
                .adaptiveContentWidth(560)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "menucard.fill")
                    .foregroundColor(.kePrimary)
                    .font(.title2)
                Text("Build your menu")
                    .font(.system(size: 28, weight: .bold))
                    .foregroundColor(.keTextPrimary)
            }
            Text("Add your items now so everything goes live together once approved.")
                .font(.subheadline)
                .foregroundColor(.keTextSecondary)
        }
    }

    private func menuItemCard(_ item: OnboardingMenuItem) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                HStack(spacing: 6) {
                    Text(formatPrice(item.priceDollars))
                        .foregroundColor(.kePrimary)
                    Text("·")
                        .foregroundColor(.keTextMuted)
                    Text(item.categoryName)
                        .foregroundColor(.keTextSecondary)
                    if !kosherTag(item).isEmpty {
                        Text("·")
                            .foregroundColor(.keTextMuted)
                        Text(kosherTag(item))
                            .foregroundColor(.kePrimary)
                    }
                }
                .font(.caption)
            }
            Spacer()
            Button {
                items.removeAll { $0.id == item.id }
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.keError.opacity(0.7))
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private var submitButton: some View {
        Button {
            Task { await submit() }
        } label: {
            Group {
                if isSubmitting {
                    ProgressView().tint(.white)
                } else {
                    Text(items.isEmpty ? "Continue without menu" : "Submit \(items.count) item\(items.count == 1 ? "" : "s")")
                        .font(.headline)
                }
            }
            .foregroundColor(.keTextOnAccent)
            .frame(maxWidth: .infinity, minHeight: 52)
            .background(isSubmitting ? Color.kePrimary.opacity(0.4) : Color.kePrimary)
            .cornerRadius(14)
        }
        .disabled(isSubmitting)
    }

    private func submit() async {
        guard !isSubmitting else { return }
        if items.isEmpty {
            onComplete()
            return
        }

        isSubmitting = true
        errorMessage = nil
        defer { isSubmitting = false }

        let grouped = Dictionary(grouping: items) { $0.categoryName }

        do {
            for (categoryName, categoryItems) in grouped {
                let category = try await APIService.shared.createCategory(categoryName)

                for item in categoryItems {
                    let priceCents = Int((Double(item.priceDollars) ?? 0) * 100)
                    guard priceCents > 0 else { continue }

                    let req = CreateMenuItemRequest(
                        categoryId: category.id,
                        name: item.name.trimmingCharacters(in: .whitespaces),
                        description: item.description.trimmingCharacters(in: .whitespaces),
                        price: priceCents,
                        imageUrl: "",
                        isMeat: item.isMeat,
                        isDairy: item.isDairy,
                        isPareve: item.isPareve,
                        isAvailable: true
                    )
                    _ = try await APIService.shared.createMenuItem(req)
                }
            }
            onComplete()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func formatPrice(_ dollars: String) -> String {
        guard let d = Double(dollars) else { return "$0.00" }
        return String(format: "$%.2f", d)
    }

    private func kosherTag(_ item: OnboardingMenuItem) -> String {
        if item.isMeat { return "Meat" }
        if item.isDairy { return "Dairy" }
        if item.isPareve { return "Pareve" }
        return ""
    }
}

// MARK: - Inline Add Item Form

private struct AddItemForm: View {
    let categories: [String]
    let onAdd: (OnboardingMenuItem) -> Void
    let onCancel: () -> Void

    @State private var name = ""
    @State private var description = ""
    @State private var price = ""
    @State private var selectedCategory = "Mains"
    @State private var isMeat = false
    @State private var isDairy = false
    @State private var isPareve = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("New Menu Item")
                .font(.subheadline.bold())
                .foregroundColor(.keTextPrimary)

            inputField("Item Name", text: $name)
            inputField("Description (optional)", text: $description)

            HStack(spacing: 12) {
                inputField("Price ($)", text: $price, keyboard: .decimalPad)
                categoryPicker
            }

            Text("Kosher Type")
                .font(.caption)
                .foregroundColor(.keTextMuted)

            HStack(spacing: 16) {
                kosherToggle("Meat", isOn: $isMeat) {
                    isDairy = false; isPareve = false
                }
                kosherToggle("Dairy", isOn: $isDairy) {
                    isMeat = false; isPareve = false
                }
                kosherToggle("Pareve", isOn: $isPareve) {
                    isMeat = false; isDairy = false
                }
            }

            if let error {
                Text(error)
                    .font(.caption)
                    .foregroundColor(.keError)
            }

            HStack(spacing: 12) {
                Button("Cancel") { onCancel() }
                    .font(.subheadline)
                    .foregroundColor(.keTextMuted)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .background(Color.keSurface)
                    .cornerRadius(10)

                Button {
                    guard !name.trimmingCharacters(in: .whitespaces).isEmpty else {
                        error = "Name is required"; return
                    }
                    guard (Double(price) ?? 0) > 0 else {
                        error = "Enter a valid price"; return
                    }
                    guard isMeat || isDairy || isPareve else {
                        error = "Select a kosher type"; return
                    }
                    onAdd(OnboardingMenuItem(
                        name: name, description: description,
                        priceDollars: price, categoryName: selectedCategory,
                        isMeat: isMeat, isDairy: isDairy, isPareve: isPareve
                    ))
                } label: {
                    Text("Add")
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextOnAccent)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(Color.kePrimary)
                        .cornerRadius(10)
                }
            }
        }
        .padding(14)
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private func inputField(_ placeholder: String, text: Binding<String>, keyboard: UIKeyboardType = .default) -> some View {
        TextField(placeholder, text: text)
            .keyboardType(keyboard)
            .foregroundColor(.keTextPrimary)
            .padding()
            .background(Color.keSurface)
            .cornerRadius(10)
    }

    private var categoryPicker: some View {
        Menu {
            ForEach(categories, id: \.self) { cat in
                Button(cat) { selectedCategory = cat }
            }
        } label: {
            HStack {
                Text(selectedCategory)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                Image(systemName: "chevron.up.chevron.down")
                    .foregroundColor(.keTextMuted)
                    .font(.caption)
            }
            .padding()
            .background(Color.keSurface)
            .cornerRadius(10)
        }
    }

    private func kosherToggle(_ label: String, isOn: Binding<Bool>, clearOthers: @escaping () -> Void) -> some View {
        Button {
            if !isOn.wrappedValue { clearOthers() }
            isOn.wrappedValue.toggle()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: isOn.wrappedValue ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isOn.wrappedValue ? .kePrimary : .keTextMuted)
                Text(label)
                    .font(.caption)
                    .foregroundColor(.keTextPrimary)
            }
        }
    }
}
