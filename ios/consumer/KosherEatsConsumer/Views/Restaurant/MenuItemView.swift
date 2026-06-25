import SwiftUI

struct MenuItemView: View {
    let item: MenuItem
    let restaurantID: String
    /// Name of the restaurant this item belongs to. Used to phrase the
    /// cross-restaurant cart-switch confirmation; nil degrades to generic copy.
    var restaurantName: String? = nil
    @EnvironmentObject var cartVM: CartViewModel
    @State private var showAddSheet = false

    var body: some View {
        Button {
            if item.isAvailable {
                showAddSheet = true
            }
        } label: {
            HStack(spacing: 14) {
                RemoteImage(url: item.imageURL, fallbackSymbol: "takeoutbag.and.cup.and.straw")
                    .frame(width: 72, height: 72)
                    .cornerRadius(Theme.cornerRadiusSmall)

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(item.name)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(item.isAvailable ? .keTextPrimary : .keTextMuted)
                            .lineLimit(1)

                        Spacer()

                        // Kashrus type indicator
                        if item.isMeat {
                            KashrusTypeIndicator(type: "M", color: .keMeat)
                        } else if item.isDairy {
                            KashrusTypeIndicator(type: "D", color: .keDairy)
                        } else if item.isPareve {
                            KashrusTypeIndicator(type: "P", color: .kePareve)
                        }
                    }

                    if !item.description.isEmpty {
                        Text(item.description)
                            .font(.system(size: 13))
                            .foregroundColor(.keTextTertiary)
                            .lineLimit(2)
                    }

                    HStack {
                        Text(item.priceFormatted)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.kePrimary)

                        Spacer()

                        if !item.isAvailable {
                            Text(String(localized: "Unavailable"))
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.keError)
                        } else {
                            Image(systemName: "plus.circle.fill")
                                .font(.system(size: 24))
                                .foregroundColor(.kePrimary)
                        }
                    }
                }
            }
            .padding(Theme.spacingSM)
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
            .opacity(item.isAvailable ? 1.0 : 0.6)
        }
        .buttonStyle(.plain)
        .disabled(!item.isAvailable)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(item.name), \(item.priceFormatted)\(item.isAvailable ? "" : ", unavailable")")
        .accessibilityHint(item.isAvailable ? String(localized: "Double tap to customize and add to cart") : "")
        .sheet(isPresented: $showAddSheet) {
            AddToCartSheet(item: item, restaurantID: restaurantID, restaurantName: restaurantName)
        }
    }
}

// MARK: - Kashrus Type Indicator

struct KashrusTypeIndicator: View {
    let type: String
    let color: Color

    private var accessibilityName: String {
        switch type {
        case "M": return String(localized: "Meat")
        case "D": return String(localized: "Dairy")
        case "P": return String(localized: "Pareve")
        default: return type
        }
    }

    var body: some View {
        Text(type)
            .font(.system(size: 11, weight: .heavy))
            .foregroundColor(.keTextOnAccent)
            .frame(width: 22, height: 22)
            .background(color)
            .cornerRadius(6)
            .accessibilityLabel(accessibilityName)
    }
}

// MARK: - Add to Cart Sheet

/// Sheet for adding a menu item to cart. Shows modifier groups with the
/// selection rules baked in (single- vs multi-select, required groups), a
/// live running total that updates as the user picks, and a notes field.
/// Required groups must be satisfied before the Add button enables.
struct AddToCartSheet: View {
    let item: MenuItem
    let restaurantID: String
    /// Name of this item's restaurant, surfaced in the cart-switch confirmation.
    var restaurantName: String? = nil
    @EnvironmentObject var cartVM: CartViewModel
    @Environment(\.dismiss) var dismiss

    @State private var quantity = 1
    @State private var notes = ""
    /// Selected modifier ids keyed by group id.
    @State private var selection: [String: Set<String>] = [:]
    /// Add-to-cart error surfaced inline so the user sees it before the sheet
    /// closes. CartView's alert is the only other renderer of cart errors and
    /// it isn't on screen while this sheet is up.
    @State private var inlineError: String?

    private var unitPrice: Int {
        let deltas = (item.modifierGroups ?? []).flatMap { group in
            (selection[group.id] ?? []).compactMap { id in
                group.modifiers.first(where: { $0.id == id })?.priceDelta
            }
        }
        return item.price + deltas.reduce(0, +)
    }

    private var totalPrice: String {
        Money.dollars(unitPrice * quantity)
    }

    private var canAdd: Bool {
        for group in (item.modifierGroups ?? []) where group.isRequired || group.minSelections > 0 {
            let picked = (selection[group.id] ?? []).count
            if picked < max(group.minSelections, group.isRequired ? 1 : 0) { return false }
        }
        return true
    }

    private var allSelectedIDs: [String] {
        (item.modifierGroups ?? []).flatMap { group in
            (selection[group.id] ?? []).sorted()
        }
    }

    private var resolvedModifiers: [SelectedModifier] {
        (item.modifierGroups ?? []).flatMap { group in
            (selection[group.id] ?? []).compactMap { id in
                guard let mod = group.modifiers.first(where: { $0.id == id }) else { return nil }
                return SelectedModifier(
                    id: mod.id,
                    groupID: group.id,
                    groupName: group.name,
                    name: mod.name,
                    priceDelta: mod.priceDelta
                )
            }
        }
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: Theme.spacingLG) {
                        header

                        if let groups = item.modifierGroups, !groups.isEmpty {
                            ForEach(groups.sorted(by: { $0.sortOrder < $1.sortOrder })) { group in
                                ModifierGroupSection(
                                    group: group,
                                    selected: Binding(
                                        get: { selection[group.id] ?? [] },
                                        set: { selection[group.id] = $0 },
                                    ),
                                )
                            }
                        }

                        VStack(alignment: .leading, spacing: 8) {
                            Text("Special Instructions")
                                .font(.subheadline.bold())
                                .foregroundColor(.keTextSecondary)
                            TextField("e.g., no onions, extra sauce", text: $notes)
                                .keTextField()
                        }
                    }
                    .padding(.horizontal)
                    .padding(.bottom, 180)
                }

                VStack {
                    Spacer()
                    addBar
                }
            }
            .navigationTitle("Customize")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Close") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
            .onAppear {
                // Pre-select each group's default options so required
                // single-select groups start valid. Only seed AVAILABLE
                // defaults, and never preselect more than the group allows —
                // a single-select group marked with multiple defaults must
                // start with at most one so the selection is valid on open.
                var initial: [String: Set<String>] = [:]
                for group in (item.modifierGroups ?? []) {
                    let defaults = group.modifiers
                        .filter { $0.isDefault && $0.isAvailable }
                        .sorted { $0.sortOrder < $1.sortOrder }
                        .prefix(max(group.maxSelections, 0))
                        .map(\.id)
                    if !defaults.isEmpty { initial[group.id] = Set(defaults) }
                }
                selection = initial
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .alert(
            cartVM.pendingRestaurantSwitch?.alertTitle ?? "",
            isPresented: Binding(
                get: { cartVM.pendingRestaurantSwitch != nil },
                set: { presented in
                    // The system clears the binding when either alert button is
                    // tapped; if it went false without us consuming the pending
                    // switch (e.g. a swipe-to-dismiss), treat it as a cancel so
                    // the existing cart is left untouched.
                    if !presented, cartVM.pendingRestaurantSwitch != nil {
                        cartVM.cancelPendingRestaurantSwitch()
                    }
                }
            ),
            presenting: cartVM.pendingRestaurantSwitch
        ) { _ in
            Button(String(localized: "Start New Cart"), role: .destructive) {
                Task {
                    inlineError = nil
                    if let err = await cartVM.confirmPendingRestaurantSwitch() {
                        inlineError = err
                    } else {
                        dismiss()
                    }
                }
            }
            Button(String(localized: "Cancel"), role: .cancel) {
                cartVM.cancelPendingRestaurantSwitch()
            }
        } message: { pending in
            Text(pending.alertMessage)
        }
    }

    /// Routes the add through the ViewModel's restaurant-switch guard. If the
    /// add would discard a cart from a different restaurant the ViewModel stashes
    /// the request and publishes `pendingRestaurantSwitch`, which drives the
    /// confirmation alert; in that case we keep the sheet open. Otherwise the add
    /// proceeds immediately: we dismiss on success, or keep the sheet open and
    /// surface the error inline so the user isn't left thinking it worked.
    private func submitAdd() async {
        inlineError = nil
        let err = await cartVM.requestAddItem(
            menuItemID: item.id,
            quantity: quantity,
            notes: notes.isEmpty ? nil : notes,
            restaurantID: restaurantID,
            restaurantName: restaurantName,
            modifierIDs: allSelectedIDs,
            itemName: item.name,
            unitPrice: item.price,
            selectedModifiers: resolvedModifiers
        )
        // Awaiting confirmation: leave the sheet up so the alert can present.
        guard cartVM.pendingRestaurantSwitch == nil else { return }
        if let err {
            inlineError = err
        } else {
            dismiss()
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            RemoteImage(url: item.imageURL, fallbackSymbol: "takeoutbag.and.cup.and.straw")
                .frame(height: 180)
                .frame(maxWidth: .infinity)
                .cornerRadius(Theme.cornerRadiusMedium)

            Text(item.name)
                .font(.title2.bold())
                .foregroundColor(.keTextPrimary)

            if !item.description.isEmpty {
                Text(item.description)
                    .font(.subheadline)
                    .foregroundColor(.keTextSecondary)
            }

            HStack(spacing: 8) {
                Text(item.priceFormatted)
                    .font(.headline)
                    .foregroundColor(.kePrimary)
                if item.isMeat {
                    KashrusTag(text: "Meat", color: .keMeat)
                } else if item.isDairy {
                    KashrusTag(text: "Dairy", color: .keDairy)
                } else if item.isPareve {
                    KashrusTag(text: "Pareve", color: .kePareve)
                }
            }
        }
    }

    private var addBar: some View {
        VStack(spacing: 0) {
            Divider().background(Color.keDivider)
            VStack(spacing: 12) {
                if let inlineError {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(.keError)
                        Text(inlineError)
                            .font(.footnote)
                            .foregroundColor(.keError)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.keError.opacity(0.12))
                    .cornerRadius(Theme.cornerRadiusSmall)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(inlineError)
                }

                HStack(spacing: 24) {
                    Button {
                        if quantity > 1 { quantity -= 1 }
                    } label: {
                        Image(systemName: "minus.circle.fill")
                            .font(.system(size: 32))
                            .foregroundColor(quantity > 1 ? .kePrimary : .keTextMuted)
                    }
                    .disabled(quantity <= 1)
                    .accessibilityLabel(String(localized: "Decrease quantity"))

                    Text("\(quantity)")
                        .font(.title3.bold())
                        .foregroundColor(.keTextPrimary)
                        .frame(width: 40)
                        .accessibilityLabel(String(localized: "Quantity: \(quantity)"))

                    Button {
                        if quantity < 99 { quantity += 1 }
                    } label: {
                        Image(systemName: "plus.circle.fill")
                            .font(.system(size: 32))
                            .foregroundColor(.kePrimary)
                    }
                    .accessibilityLabel(String(localized: "Increase quantity"))
                }

                Button {
                    Task { await submitAdd() }
                } label: {
                    if cartVM.isLoading {
                        ProgressView()
                            .frame(maxWidth: .infinity)
                    } else {
                        HStack {
                            Text(canAdd ? "Add to Cart" : "Select required options")
                            Spacer()
                            Text(totalPrice)
                        }
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle(isEnabled: canAdd && !cartVM.isLoading))
                .disabled(!canAdd || cartVM.isLoading)
            }
            .padding()
            .background(Color.keBackgroundElevated)
        }
    }
}

// MARK: - Modifier group section

/// One section per modifier group. Radio-style rows when maxSelections == 1,
/// checkbox otherwise. Header shows "Required" or "Up to N".
private struct ModifierGroupSection: View {
    let group: ModifierGroup
    @Binding var selected: Set<String>

    var body: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(group.name)
                        .font(.headline)
                        .foregroundColor(.keTextPrimary)
                    if let desc = group.description, !desc.isEmpty {
                        Text(desc)
                            .font(.caption)
                            .foregroundColor(.keTextTertiary)
                    }
                }
                Spacer()
                Text(isMandatory ? requiredLabel : rangeLabel)
                    .font(.caption.bold())
                    .foregroundColor(isMandatory ? .kePrimary : .keTextMuted)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background((isMandatory ? Color.kePrimary : Color.keTextMuted).opacity(0.15))
                    .cornerRadius(4)
            }

            VStack(spacing: 0) {
                let sortedModifiers = group.modifiers.sorted { $0.sortOrder < $1.sortOrder }
                ForEach(sortedModifiers) { mod in
                    ModifierRow(
                        modifier: mod,
                        isSelected: selected.contains(mod.id),
                        style: group.isSingleSelect ? .radio : .checkbox,
                        action: { toggle(mod) },
                    )
                    if mod.id != sortedModifiers.last?.id {
                        Divider().background(Color.keDivider)
                    }
                }
            }
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
        }
    }

    /// Mirrors AddToCartSheet.canAdd's gate: a group is mandatory if the seller
    /// flagged it required OR set a positive minimum. Keeps the badge in sync
    /// with the disabled Add button so a min'd "optional" group isn't a dead-end.
    private var isMandatory: Bool { group.isRequired || group.minSelections > 0 }

    /// Required badge text. When a minimum > 1 is set, advertise it so the user
    /// knows how many picks are needed before Add enables.
    private var requiredLabel: String {
        group.minSelections > 1
            ? String(localized: "Choose at least \(group.minSelections)")
            : String(localized: "Required")
    }

    private var rangeLabel: String {
        if group.maxSelections == 1 { return "Choose 1" }
        return "Up to \(group.maxSelections)"
    }

    private func toggle(_ mod: Modifier) {
        guard mod.isAvailable else { return }
        if group.isSingleSelect {
            selected = [mod.id]
            return
        }
        if selected.contains(mod.id) {
            selected.remove(mod.id)
        } else if selected.count < group.maxSelections {
            selected.insert(mod.id)
        }
    }
}

// MARK: - Modifier row

private struct ModifierRow: View {
    enum Style { case radio, checkbox }

    let modifier: Modifier
    let isSelected: Bool
    let style: Style
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                indicator
                Text(modifier.name)
                    .foregroundColor(.keTextPrimary)
                Spacer()
                if !modifier.isAvailable {
                    Text("Unavailable")
                        .font(.caption)
                        .foregroundColor(.keError)
                } else if !modifier.priceDeltaFormatted.isEmpty {
                    Text(modifier.priceDeltaFormatted)
                        .font(.subheadline)
                        .foregroundColor(.keTextSecondary)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!modifier.isAvailable)
        .opacity(modifier.isAvailable ? 1.0 : 0.5)
    }

    private var indicator: some View {
        Group {
            switch style {
            case .radio:
                ZStack {
                    Circle()
                        .stroke(isSelected ? Color.kePrimary : Color.keTextMuted, lineWidth: 2)
                        .frame(width: 20, height: 20)
                    if isSelected {
                        Circle()
                            .fill(Color.kePrimary)
                            .frame(width: 10, height: 10)
                    }
                }
            case .checkbox:
                ZStack {
                    RoundedRectangle(cornerRadius: 4)
                        .fill(isSelected ? Color.kePrimary : Color.clear)
                        .frame(width: 20, height: 20)
                    RoundedRectangle(cornerRadius: 4)
                        .stroke(isSelected ? Color.kePrimary : Color.keTextMuted, lineWidth: 2)
                        .frame(width: 20, height: 20)
                    Image(systemName: "checkmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundColor(.keTextOnAccent)
                        .opacity(isSelected ? 1 : 0)
                }
            }
        }
    }
}
