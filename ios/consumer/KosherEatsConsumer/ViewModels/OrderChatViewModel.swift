import Foundation

@MainActor
final class OrderChatViewModel: ObservableObject {
    @Published var messages: [ChatMessage] = []
    @Published var isSending = false
    @Published var errorMessage: String?
    @Published var sendErrorMessage: String?
    @Published var authErrorOccurred = false

    static let maxMessageLength = 2000

    let orderID: String

    private let api = APIService.shared
    private var pollTask: Task<Void, Never>?
    private var refreshAuth: (() async -> Bool)?

    init(orderID: String) {
        self.orderID = orderID
    }

    func configureAuthRefresh(_ refreshAuth: @escaping () async -> Bool) {
        self.refreshAuth = refreshAuth
    }

    func start() async {
        stop()
        authErrorOccurred = false
        errorMessage = nil
        sendErrorMessage = nil
        await fetch(allowAuthRefresh: true)
        if !authErrorOccurred {
            startPolling()
        }
    }

    func stop() {
        pollTask?.cancel()
        pollTask = nil
    }

    func dismissSendError() {
        sendErrorMessage = nil
    }

    @discardableResult
    func send(text: String) async -> Bool {
        await send(text: text, allowAuthRefresh: true)
    }

    @discardableResult
    private func send(text: String, allowAuthRefresh: Bool) async -> Bool {
        let text = text.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return false }
        guard text.count <= Self.maxMessageLength else {
            sendErrorMessage = "Message is too long"
            return false
        }

        isSending = true
        defer { isSending = false }

        do {
            let newMessage = try await api.sendChatMessage(orderID: orderID, text: text)
            messages.append(newMessage)
            authErrorOccurred = false
            errorMessage = nil
            sendErrorMessage = nil
            Haptics.impact(.light)
            return true
        } catch APIError.unauthorized {
            guard allowAuthRefresh, let refreshAuth else {
                authErrorOccurred = true
                sendErrorMessage = "Session expired"
                Haptics.error()
                return false
            }
            let refreshed = await refreshAuth()
            if refreshed {
                authErrorOccurred = false
                return await send(text: text, allowAuthRefresh: false)
            } else {
                authErrorOccurred = true
                sendErrorMessage = "Session expired"
                Haptics.error()
                return false
            }
        } catch {
            sendErrorMessage = error.localizedDescription
            Haptics.error()
            return false
        }
    }

    private func fetch(allowAuthRefresh: Bool) async {
        do {
            let serverMessages = try await api.listChatMessages(orderID: orderID)
            // Merge by ID to avoid clobbering optimistically-appended just-sent messages
            let serverIDs = Set(serverMessages.map(\.id))
            let pending = messages.filter { !serverIDs.contains($0.id) }
            messages = serverMessages + pending
            authErrorOccurred = false
            errorMessage = nil
        } catch APIError.unauthorized {
            guard allowAuthRefresh, let refreshAuth else {
                authErrorOccurred = true
                return
            }
            let refreshed = await refreshAuth()
            if refreshed {
                await fetch(allowAuthRefresh: false)
            } else {
                authErrorOccurred = true
            }
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task { [weak self] @MainActor in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                guard let self else { return }
                if Task.isCancelled || self.authErrorOccurred { break }
                await self.fetch(allowAuthRefresh: true)
                if Task.isCancelled || self.authErrorOccurred { break }
            }
        }
    }

    deinit {
        pollTask?.cancel()
    }
}
