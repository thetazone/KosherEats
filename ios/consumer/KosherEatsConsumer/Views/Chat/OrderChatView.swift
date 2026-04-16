import SwiftUI

/// Order-scoped chat screen. Used by the consumer app to message the
/// courier or restaurant about an in-progress order. Same backend endpoint
/// (`/orders/:id/chat`) that the seller + courier apps hit — all three see
/// the same thread.
///
/// Polls every 3s while the view is visible. Uses a simple request/response
/// pattern rather than websockets — fine for the MVP, and matches how
/// UberEats / DoorDash actually run their chat (polling + smart back-off).
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
                        // Auto-scroll to the newest message as they come in.
                        if let last = messages.last {
                            withAnimation {
                                proxy.scrollTo(last.id, anchor: .bottom)
                            }
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
            Text("Send a note to your driver or the restaurant.")
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
                    .foregroundColor(.white)
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
            messages = try await APIService.shared.listChatMessages(orderID: orderID)
            errorMessage = nil
        } catch {
            // Don't overwrite existing messages on poll failure — just show
            // the error inline so the user knows refresh is failing.
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func send() async {
        let text = input.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        guard text.count <= 2000 else {
            errorMessage = "Message is too long"
            return
        }
        isSending = true
        defer { isSending = false }
        do {
            let newMessage = try await APIService.shared.sendChatMessage(orderID: orderID, text: text)
            messages.append(newMessage)
            input = ""
            Haptics.impact(.light)
        } catch {
            errorMessage = error.localizedDescription
            Haptics.error()
        }
    }

    private func startPolling() {
        pollTask?.cancel()
        pollTask = Task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if Task.isCancelled { break }
                await fetch()
            }
        }
    }
}

// MARK: - Chat bubble

private struct ChatBubble: View {
    let message: ChatMessage

    /// Treat messages from this user as "mine" so they align right with the
    /// primary color; everyone else aligns left on the card background.
    private var isMine: Bool {
        // Consumer only ever sees messages from self (consumer role) as mine.
        message.senderRole == "consumer"
    }

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
