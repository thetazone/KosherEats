import Foundation

@MainActor
class DealsViewModel: ObservableObject {
    @Published var deals: [Deal] = []
    @Published var menuItems: [MenuItem] = []
    @Published var isLoading = false
    @Published var isCreating = false
    @Published var errorMessage: String?
    @Published var createSuccess = false

    func loadDeals() async {
        isLoading = true
        errorMessage = nil
        do {
            deals = try await APIService.shared.getDeals()
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func loadMenuItems() async {
        do {
            let categories = try await APIService.shared.getMenu()
            menuItems = categories.flatMap { $0.items ?? [] }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createDeal(_ request: CreateDealRequest) async {
        isCreating = true
        errorMessage = nil
        createSuccess = false
        do {
            _ = try await APIService.shared.createDeal(request)
            createSuccess = true
            await loadDeals()
        } catch {
            errorMessage = error.localizedDescription
        }
        isCreating = false
    }

    func deactivateDeal(_ dealId: String) async {
        do {
            try await APIService.shared.deactivateDeal(id: dealId)
            await loadDeals()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
