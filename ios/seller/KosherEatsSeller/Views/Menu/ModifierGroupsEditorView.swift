import SwiftUI

/// Lists all modifier groups on a single menu item and lets the seller
/// add / edit / delete them. Pushes into `ModifierGroupEditorView` for the
/// actual per-group form (name, required toggle, min/max, options list).
///
/// Groups are loaded by fetching the menu and picking out the matching item —
/// there's no dedicated "get one item" endpoint and the seller typically
/// navigates here straight from the menu screen, so the whole list is already
/// warm in their local state anyway.
struct ModifierGroupsEditorView: View {
    let itemID: String
    let itemName: String

    @State private var groups: [ModifierGroup] = []
    @State private var isLoading = true
    @State private var errorMessage: String?

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if isLoading {
                ProgressView().tint(.kePrimary)
            } else if let err = errorMessage {
                ErrorStateView(
                    message: err,
                    onRetry: { Task { await load() } },
                )
            } else if groups.isEmpty {
                emptyState
            } else {
                groupList
            }
        }
        .navigationTitle("Modifiers")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink {
                    ModifierGroupEditorView(
                        itemID: itemID,
                        existing: nil,
                        onSaved: { saved in
                            groups.append(saved)
                            groups.sort { $0.sortOrder < $1.sortOrder }
                        }
                    )
                } label: {
                    Image(systemName: "plus")
                        .foregroundColor(.kePrimary)
                }
                .accessibilityLabel("Add modifier group")
            }
        }
        .task { await load() }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
                .accessibilityHidden(true)
            Text("No modifier groups yet")
                .font(.headline)
                .foregroundColor(.keTextSecondary)
            Text("Add options like Size, Sauce, or Extras to \(itemName).")
                .font(.caption)
                .foregroundColor(.keTextMuted)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .accessibilityElement(children: .combine)
    }

    private var groupList: some View {
        ScrollView {
            VStack(spacing: 12) {
                ForEach(groups) { group in
                    NavigationLink {
                        ModifierGroupEditorView(
                            itemID: itemID,
                            existing: group,
                            onSaved: { updated in
                                if let idx = groups.firstIndex(where: { $0.id == updated.id }) {
                                    groups[idx] = updated
                                }
                            },
                            onDeleted: { groupID in
                                groups.removeAll { $0.id == groupID }
                            }
                        )
                    } label: {
                        row(for: group)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
        }
    }

    private func row(for group: ModifierGroup) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(group.name)
                        .font(.subheadline.bold())
                        .foregroundColor(.keTextPrimary)
                    if group.isRequired {
                        Text("Required")
                            .font(.caption2.bold())
                            .foregroundColor(.keTextOnAccent)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.kePrimary)
                            .cornerRadius(4)
                    }
                }
                Text("\(group.modifiers.count) option\(group.modifiers.count == 1 ? "" : "s") \u{2022} pick \(selectionSummary(group))")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundColor(.keTextMuted)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(12)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(group.name), \(group.modifiers.count) options, pick \(selectionSummary(group))\(group.isRequired ? ", required" : "")")
        .accessibilityHint("Double tap to edit")
    }

    private func selectionSummary(_ g: ModifierGroup) -> String {
        if g.minSelections == g.maxSelections {
            return "\(g.minSelections)"
        }
        return "\(g.minSelections)-\(g.maxSelections)"
    }

    private func load() async {
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            let categories = try await APIService.shared.getMenu()
            let allItems = categories.flatMap { $0.items ?? [] }
            if let match = allItems.first(where: { $0.id == itemID }) {
                groups = (match.modifierGroups ?? []).sorted { $0.sortOrder < $1.sortOrder }
            } else {
                errorMessage = "Item not found."
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Form for creating or editing a single modifier group. Handles both modes
/// to avoid duplicating the rather-long options list UI.
struct ModifierGroupEditorView: View {
    let itemID: String
    let existing: ModifierGroup?
    var onSaved: (ModifierGroup) -> Void
    var onDeleted: ((String) -> Void)? = nil

    /// Prefix for client-only ids assigned to not-yet-saved options so each row
    /// has a stable, unique SwiftUI identity. Stripped back to `nil` before the
    /// request is sent so the backend treats them as inserts.
    private static let newOptionIDPrefix = "new-"

    @Environment(\.dismiss) private var dismiss

    @State private var name: String = ""
    @State private var description: String = ""
    @State private var isRequired: Bool = false
    @State private var minSelections: Int = 0
    @State private var maxSelections: Int = 1
    @State private var sortOrder: Int = 0
    @State private var options: [Modifier] = []
    @State private var saving = false
    @State private var deleting = false
    @State private var errorMessage: String?
    @State private var showDeleteConfirm = false

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 20) {
                    formSection("Group Name") {
                        TextField("e.g., Size", text: $name)
                            .foregroundColor(.keTextPrimary)
                            .padding()
                            .background(Color.keCard)
                            .cornerRadius(12)
                            .accessibilityLabel("Group name")
                    }

                    formSection("Selection Rules") {
                        HStack {
                            Text("Required")
                                .foregroundColor(.keTextPrimary)
                            Spacer()
                            Toggle("", isOn: $isRequired)
                                .tint(.kePrimary)
                                .labelsHidden()
                                .onChange(of: isRequired) { _, req in
                                    if req && minSelections < 1 { minSelections = 1 }
                                    if maxSelections < minSelections { maxSelections = minSelections }
                                }
                        }
                        .padding()
                        .background(Color.keCard)
                        .cornerRadius(12)

                        stepperRow("Minimum picks", value: $minSelections, range: 0...10)
                            .onChange(of: minSelections) { _, v in
                                if maxSelections < v { maxSelections = v }
                                if isRequired && v < 1 { isRequired = false }
                            }

                        stepperRow("Maximum picks", value: $maxSelections, range: max(1, minSelections)...20)
                    }

                    formSection("Options") {
                        VStack(spacing: 10) {
                            ForEach($options) { $opt in
                                optionRow(for: $opt)
                            }
                            Button {
                                options.append(Modifier(
                                    id: Self.newOptionIDPrefix + UUID().uuidString,
                                    groupId: existing?.id ?? "",
                                    name: "",
                                    priceDelta: 0,
                                    isDefault: false,
                                    isAvailable: true,
                                    sortOrder: options.count
                                ))
                            } label: {
                                Label("Add Option", systemImage: "plus.circle")
                                    .foregroundColor(.kePrimary)
                                    .padding(.vertical, 8)
                            }
                        }
                    }

                    if let err = errorMessage {
                        Text(err)
                            .font(.caption)
                            .foregroundColor(.keError)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        Task { await save() }
                    } label: {
                        Text(existing == nil ? "Create Group" : "Save Changes")
                            .font(.headline)
                            .foregroundColor(.keTextOnAccent)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(canSave ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                            .cornerRadius(14)
                    }
                    .disabled(!canSave || saving)

                    if existing != nil {
                        Button(role: .destructive) {
                            showDeleteConfirm = true
                        } label: {
                            Text("Delete Group")
                                .font(.subheadline.bold())
                                .foregroundColor(.keError)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                        }
                        .disabled(deleting)
                    }
                }
                .padding()
            }
        }
        .navigationTitle(existing == nil ? "New Group" : "Edit Group")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .onAppear { hydrate() }
        .confirmationDialog("Delete this group?", isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("Delete", role: .destructive) { Task { await delete() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Customers will no longer see these options on this item.")
        }
    }

    private func hydrate() {
        guard let g = existing else { return }
        name = g.name
        description = g.description
        isRequired = g.isRequired
        minSelections = g.minSelections
        maxSelections = g.maxSelections
        sortOrder = g.sortOrder
        options = g.modifiers
    }

    private func optionRow(for binding: Binding<Modifier>) -> some View {
        let opt = binding.wrappedValue
        return HStack(spacing: 8) {
            TextField("Option name", text: binding.name)
                .foregroundColor(.keTextPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel("Option name")

            HStack(spacing: 2) {
                Text("$")
                    .foregroundColor(.keTextMuted)
                    .font(.caption)
                TextField("0.00", text: Binding(
                    get: { String(format: "%.2f", Double(opt.priceDelta) / 100) },
                    set: { newVal in
                        let filtered = newVal.filter { $0.isNumber || $0 == "." }
                        let cents = max(0, Int(round((Double(filtered) ?? 0) * 100)))
                        binding.wrappedValue.priceDelta = cents
                    }
                ))
                .keyboardType(.decimalPad)
                .foregroundColor(.keTextPrimary)
                .frame(width: 60)
                .multilineTextAlignment(.trailing)
                .accessibilityLabel("Price adjustment in dollars")
            }

            Button {
                // Every row now has a unique id (server id, or a local "new-"
                // id for unsaved options), so matching by id removes exactly
                // the tapped row.
                options.removeAll { $0.id == opt.id }
            } label: {
                Image(systemName: "minus.circle.fill")
                    .foregroundColor(.keError)
            }
            .accessibilityLabel("Remove \(opt.name.isEmpty ? "option" : opt.name)")
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(10)
    }

    private func stepperRow(_ label: String, value: Binding<Int>, range: ClosedRange<Int>) -> some View {
        HStack {
            Text(label)
                .foregroundColor(.keTextPrimary)
            Spacer()
            Stepper("\(value.wrappedValue)", value: value, in: range)
                .labelsHidden()
            Text("\(value.wrappedValue)")
                .foregroundColor(.keTextPrimary)
                .frame(width: 24)
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(12)
    }

    private var canSave: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty
            && maxSelections >= minSelections
            && (!isRequired || minSelections >= 1)
            && !options.isEmpty
            && options.allSatisfy { !$0.name.trimmingCharacters(in: .whitespaces).isEmpty }
            && options.allSatisfy { $0.priceDelta >= 0 }
    }

    private func save() async {
        saving = true
        errorMessage = nil
        defer { saving = false }

        let payload = ModifierGroupRequest(
            name: name,
            description: description,
            isRequired: isRequired,
            minSelections: minSelections,
            maxSelections: maxSelections,
            sortOrder: sortOrder,
            modifiers: options.enumerated().map { (idx, opt) in
                // Send nil id for new options (blank, or a client-only "new-"
                // id) so the backend inserts them rather than failing to update
                // a non-existent row.
                let isNew = opt.id.isEmpty || opt.id.hasPrefix(Self.newOptionIDPrefix)
                return ModifierOptionRequest(
                    id: isNew ? nil : opt.id,
                    name: opt.name,
                    priceDelta: opt.priceDelta,
                    isDefault: opt.isDefault,
                    isAvailable: opt.isAvailable,
                    sortOrder: idx
                )
            }
        )

        do {
            let saved: ModifierGroup
            if let g = existing {
                saved = try await APIService.shared.updateModifierGroup(groupID: g.id, payload)
            } else {
                saved = try await APIService.shared.createModifierGroup(itemID: itemID, payload)
            }
            Haptics.notify(.success)
            onSaved(saved)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func delete() async {
        guard let g = existing else { return }
        deleting = true
        errorMessage = nil
        defer { deleting = false }
        do {
            try await APIService.shared.deleteModifierGroup(groupID: g.id)
            Haptics.notify(.success)
            onDeleted?(g.id)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func formSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            content()
        }
    }
}
