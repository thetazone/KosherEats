import Foundation
import SwiftUI

@MainActor
class HomeViewModel: ObservableObject {
    @Published var restaurants: [Restaurant] = []
    @Published var filteredRestaurants: [Restaurant] = []
    @Published var featuredRestaurants: [Restaurant] = []
    @Published var searchText = ""
    @Published var selectedCuisine: String?
    @Published var isLoading = false
    @Published var errorMessage: String?

    let cuisineFilters = [
        "All", "Israeli", "Middle Eastern", "Pizza", "Sushi",
        "Deli", "Steakhouse", "Chinese", "Mexican", "Bakery", "Cafe"
    ]

    private let api = APIService.shared

    func loadRestaurants() async {
        isLoading = true
        errorMessage = nil

        do {
            restaurants = try await api.listRestaurants()
            featuredRestaurants = Array(
                restaurants
                    .filter { $0.isOpen && $0.isActive }
                    .sorted { $0.rating > $1.rating }
                    .prefix(5)
            )
            applyFilters()
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func search() async {
        guard !searchText.trimmingCharacters(in: .whitespaces).isEmpty else {
            applyFilters()
            return
        }

        isLoading = true
        do {
            let results = try await api.searchRestaurants(query: searchText)
            filteredRestaurants = results
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func selectCuisine(_ cuisine: String) {
        if cuisine == "All" {
            selectedCuisine = nil
        } else {
            selectedCuisine = selectedCuisine == cuisine ? nil : cuisine
        }
        applyFilters()
    }

    private func applyFilters() {
        var results = restaurants.filter { $0.isActive }

        if let cuisine = selectedCuisine {
            results = results.filter { restaurant in
                restaurant.cuisineType.contains { $0.localizedCaseInsensitiveContains(cuisine) }
            }
        }

        if !searchText.isEmpty {
            results = results.filter {
                $0.name.localizedCaseInsensitiveContains(searchText) ||
                $0.description.localizedCaseInsensitiveContains(searchText)
            }
        }

        filteredRestaurants = results
    }
}
