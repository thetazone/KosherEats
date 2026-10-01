import Foundation

/// Drives the Payouts screen: Stripe Connect setup state, the period summary
/// (This week / Last week / This month), and the paginated per-order ledger.
/// Each section loads and fails independently so one slow/broken endpoint
/// never blanks the others.
@MainActor
final class PayoutsViewModel: ObservableObject {
    // MARK: Setup status
    @Published var status: SellerPayoutStatus?
    @Published var isLoadingStatus = false
    @Published var statusError: String?

    // MARK: Onboarding (Stripe hosted flow)
    @Published var isStartingOnboarding = false
    @Published var onboardingError: String?
    @Published var safariDestination: SafariDestination?

    // MARK: Summary
    @Published private(set) var period: PayoutPeriod = .thisWeek
    @Published var summary: PayoutSummary?
    @Published var isLoadingSummary = false
    @Published var summaryError: String?

    // MARK: Ledger
    @Published var lines: [PayoutLine] = []
    @Published var nextCursor: String?
    @Published var isLoadingLines = false
    @Published var isLoadingMore = false
    @Published var linesError: String?
    @Published var hasLoadedLines = false

    static let pageSize = 50

    /// Drop responses from a superseded request (e.g. the seller flips
    /// This week → This month before the first summary returns).
    private var summaryGeneration = 0
    private var linesGeneration = 0

    var setupState: SellerPayoutStatus.SetupState {
        status?.setupState ?? .notSetUp
    }

    // MARK: - Loading

    func loadAll() async {
        async let s: Void = refreshStatus()
        async let m: Void = loadSummary()
        async let l: Void = loadLines()
        _ = await (s, m, l)
    }

    func refreshStatus() async {
        isLoadingStatus = true
        defer { isLoadingStatus = false }
        do {
            status = try await APIService.shared.getPayoutStatus()
            statusError = nil
        } catch {
            guard !Self.isCancellation(error) else { return }
            statusError = "Couldn't check your payout status. Pull down to try again."
        }
    }

    func selectPeriod(_ newPeriod: PayoutPeriod) {
        guard newPeriod != period else { return }
        period = newPeriod
        // Clear so the card shows a skeleton instead of last period's totals
        // under the new period's title.
        summary = nil
        Task { await loadSummary() }
    }

    func loadSummary() async {
        summaryGeneration &+= 1
        let gen = summaryGeneration
        let range = period.wireRange()
        isLoadingSummary = true
        summaryError = nil
        do {
            let fetched = try await APIService.shared.getPayoutSummary(from: range.from, to: range.to)
            guard gen == summaryGeneration else { return }
            summary = fetched
        } catch {
            guard gen == summaryGeneration, !Self.isCancellation(error) else { return }
            summaryError = Self.message(for: error)
        }
        if gen == summaryGeneration { isLoadingSummary = false }
    }

    /// First page of the ledger (replaces whatever is loaded).
    func loadLines() async {
        linesGeneration &+= 1
        let gen = linesGeneration
        isLoadingLines = true
        linesError = nil
        do {
            let page = try await APIService.shared.getPayoutLines(limit: Self.pageSize)
            guard gen == linesGeneration else { return }
            lines = Self.dedupe(page.lines)
            nextCursor = page.nextCursor
            hasLoadedLines = true
        } catch {
            guard gen == linesGeneration, !Self.isCancellation(error) else { return }
            linesError = Self.message(for: error)
        }
        if gen == linesGeneration { isLoadingLines = false }
    }

    /// Next page, triggered when the last row scrolls into view.
    func loadMore() async {
        guard let cursor = nextCursor, !isLoadingMore, !isLoadingLines else { return }
        let gen = linesGeneration
        isLoadingMore = true
        linesError = nil
        defer { isLoadingMore = false }
        do {
            let page = try await APIService.shared.getPayoutLines(limit: Self.pageSize, cursor: cursor)
            guard gen == linesGeneration else { return }
            lines = Self.dedupe(lines + page.lines)
            nextCursor = page.nextCursor
        } catch {
            guard gen == linesGeneration, !Self.isCancellation(error) else { return }
            linesError = Self.message(for: error)
        }
    }

    // MARK: - Stripe onboarding

    /// Create (or reuse) the restaurant's Connect account, then open Stripe's
    /// hosted onboarding link in SFSafariViewController.
    func startOnboarding() async {
        guard !isStartingOnboarding else { return }
        isStartingOnboarding = true
        onboardingError = nil
        defer { isStartingOnboarding = false }
        do {
            status = try await APIService.shared.createPayoutAccount()
            let link = try await APIService.shared.getPayoutLink()
            guard let url = URL(string: link.url), url.scheme != nil else {
                onboardingError = "Stripe didn't return a valid setup link. Please try again."
                return
            }
            safariDestination = SafariDestination(url: url)
        } catch {
            guard !Self.isCancellation(error) else { return }
            onboardingError = Self.message(for: error)
        }
    }

    /// Called when the Stripe sheet closes (Done, swipe, or completion).
    /// Lines flip from "awaiting_account" once payouts become ready, so the
    /// ledger is refreshed alongside the status.
    func onboardingDismissed() async {
        async let s: Void = refreshStatus()
        async let l: Void = loadLines()
        _ = await (s, l)
    }

    // MARK: - Helpers

    private static func dedupe(_ input: [PayoutLine]) -> [PayoutLine] {
        var seen = Set<String>()
        return input.filter { seen.insert($0.id).inserted }
    }

    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        return false
    }

    private static func message(for error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}
