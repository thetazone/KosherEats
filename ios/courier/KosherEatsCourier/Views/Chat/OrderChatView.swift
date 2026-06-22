import SwiftUI

/// Order-scoped chat view for the courier app. Same shape as the consumer
/// version — only the "isMine" rule differs (messages from the courier
/// role align right here; consumer messages align left).
struct OrderChatView: View {
    let orderID: String

    @State private var messages: [ChatMessage] = []
    @State private var input: String = ""
    @State private var isSending = false
    @State private var errorMessage: String?
    @State private var pollTask: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 0) {
            if messages.isEmpty {
                emptyState
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(messages) { message in
                                ChatBubble(message: message)
                                    .id(message.id)
                            }
                        }
                        .padding()
                    }
                    .onChange(of: messages.count) { _, _ in
                        if let last = messages.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
            }

            if let err = errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.keError)
                    .padding(.horizontal)
            }

            inputBar
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await fetch()
            startPolling()
        }
        .onDisappear { pollTask?.cancel() }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "bubble.left.and.bubble.right")
                .font(.system(size: 48))
                .foregroundColor(.keTextMuted)
            Text("No messages yet")
                .font(.headline)
                .foregroundColor(.keTextSecondary)
            Text("Send a note to the customer or restaurant.")
                .font(.caption)
                .foregroundColor(.keTextTertiary)
            Spacer()
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("Type a message…", text: $input, axis: .vertical)
                .lineLimit(1...4)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.keCard)
                .cornerRadius(20)
                .foregroundColor(.keTextPrimary)

            Button {
                Task { await send() }
            } label: {
                Image(systemName: "paperplane.fill")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundColor(.keTextOnAccent)
                    .frame(width: 40, height: 40)
                    .background(canSend ? Color.kePrimary : Color.keTextMuted)
                    .clipShape(Circle())
            }
            .disabled(!canSend)
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(Color.keBackgroundElevated)
    }

    private var canSend: Bool {
        !input.trimmingCharacters(in: .whitespaces).isEmpty && !isSending
    }

    private func fetch() async {
        do {
            let serverMessages = try await APIService.shared.listChatMessages(orderID: orderID)
            // Merge by ID to avoid clobbering optimistically-appended just-sent messages
            let serverIDs = Set(serverMessages.map(\.id))
            let pending = messages.filter { !serverIDs.contains($0.id) }
            messages = (serverMessages + pending).sorted { $0.createdAt < $1.createdAt }
            errorMessage = nil
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func send() async {
        let text = String(input.trimmingCharacters(in: .whitespaces).prefix(2000))
        guard !text.isEmpty else { return }
        isSending = true
        defer { isSending = false }
        do {
            let newMessage = try await APIService.shared.sendChatMessage(orderID: orderID, text: text)
            messages.append(newMessage)
            input = ""
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 3_000_000_000)
                } catch {
                    break
                }
                if Task.isCancelled { break }
                if !isSending { await fetch() }
            }
        }
    }
}

private struct ChatBubble: View {
    let message: ChatMessage

    /// Messages from the courier role are "mine" in this app.
    private var isMine: Bool { message.senderRole == "courier" }

    var body: some View {
        HStack {
            if isMine { Spacer(minLength: 48) }
            VStack(alignment: isMine ? .trailing : .leading, spacing: 4) {
                if !isMine {
                    Text(message.senderLabel)
                        .font(.caption2.bold())
                        .foregroundColor(.kePrimary)
                }
                Text(message.text)
                    .foregroundColor(isMine ? .white : .keTextPrimary)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(isMine ? Color.kePrimary : Color.keCard)
                    .cornerRadius(18)
                Text(message.shortTime)
                    .font(.caption2)
                    .foregroundColor(.keTextMuted)
            }
            if !isMine { Spacer(minLength: 48) }
        }
    }
}
