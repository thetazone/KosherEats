import SwiftUI

/// Order-scoped chat screen. Used by the consumer app to message the
/// courier or restaurant about an in-progress order. Same backend endpoint
/// (`/orders/:id/chat`) that the seller + courier apps hit — all three see
/// the same thread, with the polling lifecycle owned by the view model.
struct OrderChatView: View {
    let orderID: String
    @StateObject private var vm: OrderChatViewModel

    @State private var input: String = ""
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var authVM: AuthViewModel

    init(orderID: String) {
        self.orderID = orderID
        _vm = StateObject(wrappedValue: OrderChatViewModel(orderID: orderID))
    }

    var body: some View {
        VStack(spacing: 0) {
            if vm.messages.isEmpty {
                emptyState
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(vm.messages) { message in
                                ChatBubble(message: message)
                                    .id(message.id)
                            }
                        }
                        .padding()
                    }
                    .onChange(of: vm.messages.count) { _, _ in
                        // Auto-scroll to the newest message as they come in.
                        if let last = vm.messages.last {
                            withAnimation {
                                proxy.scrollTo(last.id, anchor: .bottom)
                            }
                        }
                    }
                }
            }

            if vm.authErrorOccurred {
                Button {
                    dismiss()
                } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "lock.fill")
                        Text(String(localized: "Session expired — tap to sign in again"))
                            .fontWeight(.semibold)
                        Spacer()
                        Image(systemName: "chevron.right")
                    }
                    .font(.subheadline)
                    .foregroundColor(.keTextOnAccent)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(Color.keError)
                }
            } else if let err = vm.sendErrorMessage {
                HStack(spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill")
                    Text(err)
                        .font(.caption)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Button {
                        vm.dismissSendError()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.caption.bold())
                    }
                }
                .foregroundColor(.keError)
                .padding(.horizontal)
                .padding(.vertical, 6)
            } else if let err = vm.errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundColor(.keError)
                    .padding(.horizontal)
            }

            inputBar
                .disabled(vm.authErrorOccurred)
        }
        .background(Color.keBackground.ignoresSafeArea())
        .navigationTitle("Chat")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            vm.configureAuthRefresh { await authVM.refreshToken() }
            await vm.start()
        }
        .onDisappear { vm.stop() }
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
            TextField(String(localized: "Type a message…"), text: $input, axis: .vertical)
                .lineLimit(1...4)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.keCard)
                .cornerRadius(20)
                .foregroundColor(.keTextPrimary)

            Button {
                Task {
                    let didSend = await vm.send(text: input)
                    if didSend {
                        input = ""
                    }
                }
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
        !input.trimmingCharacters(in: .whitespaces).isEmpty && !vm.isSending && !vm.authErrorOccurred
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
