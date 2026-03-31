import SwiftUI

struct MenuManagementView: View {
    @StateObject private var vm = MenuViewModel()
    @State private var showAddItem = false
    @State private var editingItem: MenuItem?
    @State private var showAddCategory = false
    @State private var newCategoryName = ""
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                if vm.isLoading && vm.categories.isEmpty {
                    ProgressView()
                        .progressViewStyle(CircularProgressViewStyle(tint: .kePrimary))
                } else if vm.categories.isEmpty {
                    emptyState
                } else {
                    menuList
                }
            }
            .navigationTitle("Menu")
            .navigationBarTitleDisplayMode(.large)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            showAddItem = true
                        } label: {
                            Label("Add Item", systemImage: "plus.circle")
                        }

                        Button {
                            showAddCategory = true
                        } label: {
                            Label("Add Category", systemImage: "folder.badge.plus")
                        }
                    } label: {
                        Image(systemName: "plus")
                            .foregroundColor(.kePrimary)
                    }
                }
            }
            .searchable(text: $searchText, prompt: "Search menu items")
            .refreshable {
                await vm.load()
            }
            .task {
                await vm.load()
            }
            .sheet(isPresented: $showAddItem) {
                MenuItemFormView(
                    categories: vm.categories,
                    onSave: { cat, name, desc, price, meat, dairy, pareve in
                        Task {
                            let success = await vm.createItem(
                                categoryId: cat,
                                name: name,
                                description: desc,
                                price: price,
                                isMeat: meat,
                                isDairy: dairy,
                                isPareve: pareve
                            )
                            if success { showAddItem = false }
                        }
                    }
                )
            }
            .sheet(item: $editingItem) { item in
                MenuItemFormView(
                    categories: vm.categories,
                    existingItem: item,
                    onSave: { cat, name, desc, price, meat, dairy, pareve in
                        Task {
                            let success = await vm.updateItem(
                                id: item.id,
                                categoryId: cat,
                                name: name,
                                description: desc,
                                price: price,
                                isMeat: meat,
                                isDairy: dairy,
                                isPareve: pareve,
                                isAvailable: item.isAvailable
                            )
                            if success { editingItem = nil }
                        }
                    }
                )
            }
            .alert("New Category", isPresented: $showAddCategory) {
                TextField("Category name", text: $newCategoryName)
                Button("Cancel", role: .cancel) {
                    newCategoryName = ""
                }
                Button("Create") {
                    Task {
                        await vm.createCategory(name: newCategoryName)
                        newCategoryName = ""
                    }
                }
            }
        }
    }

    // MARK: - Menu List

    private var menuList: some View {
        ScrollView {
            LazyVStack(spacing: 20) {
                ForEach(vm.categories) { category in
                    let items = filteredItems(for: category)
                    if !items.isEmpty || searchText.isEmpty {
                        categorySection(category, items: items)
                    }
                }
            }
            .padding()
        }
    }

    private func categorySection(_ category: MenuCategory, items: [MenuItem]) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(category.name)
                    .font(.title3.bold())
                    .foregroundColor(.keTextPrimary)

                Spacer()

                Text("\(items.count) items")
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
            }

            if items.isEmpty {
                Text("No items in this category")
                    .font(.caption)
                    .foregroundColor(.keTextMuted)
                    .padding(.vertical, 20)
                    .frame(maxWidth: .infinity)
            } else {
                ForEach(items) { item in
                    menuItemRow(item)
                }
            }
        }
    }

    private func menuItemRow(_ item: MenuItem) -> some View {
        HStack(spacing: 12) {
            // Item Info
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Text(item.name)
                        .font(.subheadline.bold())
                        .foregroundColor(item.isAvailable ? .keTextPrimary : .keTextMuted)

                    kosherTag(item)
                }

                if !item.description.isEmpty {
                    Text(item.description)
                        .font(.caption)
                        .foregroundColor(.keTextSecondary)
                        .lineLimit(2)
                }

                Text(String(format: "$%.2f", item.price))
                    .font(.subheadline.bold())
                    .foregroundColor(.kePrimary)
            }

            Spacer()

            // Availability Toggle
            VStack(spacing: 6) {
                Toggle("", isOn: Binding(
                    get: { item.isAvailable },
                    set: { _ in
                        Task { await vm.toggleAvailability(item: item) }
                    }
                ))
                .tint(.kePrimary)
                .labelsHidden()

                Text(item.isAvailable ? "Available" : "Unavailable")
                    .font(.caption2)
                    .foregroundColor(item.isAvailable ? .keSuccess : .keTextMuted)
            }

            // Actions Menu
            Menu {
                Button {
                    editingItem = item
                } label: {
                    Label("Edit", systemImage: "pencil")
                }

                Button(role: .destructive) {
                    Task { await vm.deleteItem(id: item.id) }
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundColor(.keTextMuted)
                    .frame(width: 32, height: 32)
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(12)
        .opacity(item.isAvailable ? 1 : 0.7)
    }

    private func kosherTag(_ item: MenuItem) -> some View {
        Text(item.kosherTag)
            .font(.caption2.bold())
            .foregroundColor(kosherTagColor(item))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(kosherTagColor(item).opacity(0.15))
            .cornerRadius(4)
    }

    private func kosherTagColor(_ item: MenuItem) -> Color {
        if item.isMeat { return .keError }
        if item.isDairy { return .blue }
        return .keSuccess
    }

    private func filteredItems(for category: MenuCategory) -> [MenuItem] {
        let items = category.items ?? []
        if searchText.isEmpty { return items }
        return items.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) ||
            $0.description.localizedCaseInsensitiveContains(searchText)
        }
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "menucard")
                .font(.system(size: 56))
                .foregroundColor(.keTextMuted)

            Text("No menu items yet")
                .font(.headline)
                .foregroundColor(.keTextSecondary)

            Text("Add categories and items to build your menu")
                .font(.subheadline)
                .foregroundColor(.keTextMuted)
                .multilineTextAlignment(.center)

            Button {
                showAddCategory = true
            } label: {
                Text("Create First Category")
                    .font(.subheadline.bold())
                    .foregroundColor(.white)
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(Color.kePrimary)
                    .cornerRadius(10)
            }
        }
        .padding()
    }
}
