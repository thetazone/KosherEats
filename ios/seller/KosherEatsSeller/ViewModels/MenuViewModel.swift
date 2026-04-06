import Foundation
import SwiftUI

@MainActor
class MenuViewModel: ObservableObject {
    @Published var categories: [MenuCategory] = []
    @Published var allItems: [MenuItem] = []
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var successMessage: String?

    func load() async {
        isLoading = true
        errorMessage = nil

        do {
            let cats = try await APIService.shared.getMenu()
            self.categories = cats
            self.allItems = cats.flatMap { $0.items ?? [] }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func createItem(
        categoryId: String,
        name: String,
        description: String,
        price: Int, // cents
        imageUrl: String,
        isMeat: Bool,
        isDairy: Bool,
        isPareve: Bool
    ) async -> Bool {
        isLoading = true
        errorMessage = nil

        let request = CreateMenuItemRequest(
            categoryId: categoryId,
            name: name,
            description: description,
            price: price,
            imageUrl: imageUrl,
            isMeat: isMeat,
            isDairy: isDairy,
            isPareve: isPareve,
            isAvailable: true
        )

        do {
            let _ = try await APIService.shared.createMenuItem(request)
            successMessage = "Item created successfully"
            await load()
            return true
        } catch {
            errorMessage = error.localizedDescription
            isLoading = false
            return false
        }
    }

    func updateItem(
        id: String,
        categoryId: String,
        name: String,
        description: String,
        price: Int, // cents
        imageUrl: String,
        isMeat: Bool,
        isDairy: Bool,
        isPareve: Bool,
        isAvailable: Bool
    ) async -> Bool {
        isLoading = true
        errorMessage = nil

        let request = CreateMenuItemRequest(
            categoryId: categoryId,
            name: name,
            description: description,
            price: price,
            imageUrl: imageUrl,
            isMeat: isMeat,
            isDairy: isDairy,
            isPareve: isPareve,
            isAvailable: isAvailable
        )

        do {
            let _ = try await APIService.shared.updateMenuItem(id: id, request)
            successMessage = "Item updated successfully"
            await load()
            return true
        } catch {
            errorMessage = error.localizedDescription
            isLoading = false
            return false
        }
    }

    func deleteItem(id: String) async {
        do {
            try await APIService.shared.deleteMenuItem(id: id)
            successMessage = "Item deleted"
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func toggleAvailability(item: MenuItem) async {
        do {
            let _ = try await APIService.shared.toggleItemAvailability(
                id: item.id,
                available: !item.isAvailable
            )
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createCategory(name: String) async {
        do {
            let _ = try await APIService.shared.createCategory(name)
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func deleteCategory(id: String) async {
        do {
            try await APIService.shared.deleteCategory(id: id)
            await load()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
