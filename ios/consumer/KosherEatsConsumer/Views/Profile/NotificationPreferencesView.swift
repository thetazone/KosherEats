import SwiftUI
import UserNotifications

/// Preferences screen for consumer push notifications. Three categories map
/// to the backend notification_preferences table; each toggle PUTs the full
/// object (backend requires all three fields to avoid partial-update races).
///
/// iOS-level permission is surfaced separately — if the user has denied push
/// system-wide, an info banner offers a deep link to Settings, because our
/// toggles can't override the OS.
struct NotificationPreferencesView: View {
    @State private var prefs: APIService.NotificationPreferences = .allOn
    @State private var isLoading = true
    @State private var isSaving = false
    @State private var errorMessage: String?
    @State private var systemAuthorized = true
    @State private var lastSyncedPrefs: APIService.NotificationPreferences = .allOn
    @State private var saveGeneration = 0
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            Color.keBackground.ignoresSafeArea()

            if isLoading {
                ProgressView().tint(.kePrimary)
            } else {
                ScrollView {
                    VStack(spacing: Theme.spacingLG) {
                        if !systemAuthorized {
                            systemDisabledBanner
                        }

                        VStack(spacing: 0) {
                            toggleRow(
                                title: "Order updates",
                                subtitle: "Status changes, courier pickup, delivery.",
                                icon: "bag.fill",
                                isOn: Binding(
                                    get: { prefs.orderUpdates },
                                    set: { newValue in
                                        prefs.orderUpdates = newValue
                                        queueSave()
                                    }
                                )
                            )
                            Divider().background(Color.keDivider).padding(.leading, 58)
                            toggleRow(
                                title: "Chat messages",
                                subtitle: "Messages from the restaurant or your courier.",
                                icon: "bubble.left.fill",
                                isOn: Binding(
                                    get: { prefs.chatMessages },
                                    set: { newValue in
                                        prefs.chatMessages = newValue
                                        queueSave()
                                    }
                                )
                            )
                            Divider().background(Color.keDivider).padding(.leading, 58)
                            toggleRow(
                                title: "Promotions",
                                subtitle: "Deals, new restaurants, occasional offers.",
                                icon: "tag.fill",
                                isOn: Binding(
                                    get: { prefs.promotions },
                                    set: { newValue in
                                        prefs.promotions = newValue
                                        queueSave()
                                    }
                                )
                            )
                        }
                        .background(Color.keCard)
                        .cornerRadius(Theme.cornerRadiusMedium)
                        .padding(.horizontal)

                        if let error = errorMessage {
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.keError)
                                .padding(.horizontal)
                        }

                        Spacer(minLength: 40)
                    }
                    .padding(.top, Theme.spacingMD)
                }
            }
        }
        .navigationTitle("Notifications")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            await refreshSystemAuthorization()
            await load()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .active {
                Task { await refreshSystemAuthorization() }
            }
        }
    }

    private var systemDisabledBanner: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.circle.fill")
                .foregroundColor(.keWarning)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text("Push notifications are off")
                    .font(.subheadline.bold())
                    .foregroundColor(.keTextPrimary)
                Text("Enable notifications in iOS Settings to receive these pushes.")
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
                .font(.caption.bold())
                .foregroundColor(.kePrimary)
                .padding(.top, 2)
            }
            Spacer()
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .padding(.horizontal)
    }

    private func toggleRow(title: String, subtitle: String, icon: String, isOn: Binding<Bool>) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(.kePrimary)
                .frame(width: 28)
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundColor(.keTextPrimary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(.keTextSecondary)
            }
            Spacer()
            Toggle(title, isOn: isOn)
                .labelsHidden()
                .tint(.kePrimary)
                .disabled(isSaving)
                .accessibilityLabel(title)
                .accessibilityHint(subtitle)
        }
        .padding(16)
    }

    private func load() async {
        do {
            prefs = try await APIService.shared.getNotificationPreferences()
            lastSyncedPrefs = prefs
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
        isLoading = false
    }

    private func queueSave() {
        saveGeneration += 1
        let generation = saveGeneration
        let candidate = prefs
        Task { await save(candidate, generation: generation) }
    }

    private func save(_ candidate: APIService.NotificationPreferences, generation: Int) async {
        isSaving = true
        defer {
            if generation == saveGeneration {
                isSaving = false
            }
        }
        errorMessage = nil
        do {
            let saved = try await APIService.shared.updateNotificationPreferences(candidate)
            guard generation == saveGeneration else { return }
            prefs = saved
            lastSyncedPrefs = saved
        } catch {
            guard generation == saveGeneration else { return }
            prefs = lastSyncedPrefs
            errorMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func refreshSystemAuthorization() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        systemAuthorized = settings.authorizationStatus == .authorized
            || settings.authorizationStatus == .provisional
            || settings.authorizationStatus == .ephemeral
    }
}
