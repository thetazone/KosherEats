import SwiftUI
import PhotosUI

struct CreateDealView: View {
    let onCreated: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = DealsViewModel()

    private static let isoFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    @State private var title = ""
    @State private var description = ""
    @State private var discountType: DiscountType = .percentage
    @State private var discountValue = ""
    @State private var minOrderAmount = ""
    @State private var expiresAt = Calendar.current.date(byAdding: .day, value: 7, to: Date()) ?? Date()
    @State private var selectedItem: PhotosPickerItem?
    @State private var selectedImage: UIImage?
    @State private var imageUrl = ""
    @State private var isUploading = false
    @State private var localError: String?

    private var canSubmit: Bool {
        guard !title.isEmpty, title.count <= 100 else { return false }
        if discountType != .bogo {
            if discountType == .fixed {
                guard let v = Double(discountValue), v > 0 else { return false }
            } else {
                guard let v = Int(discountValue), v > 0 else { return false }
                if v > 100 { return false }
            }
        }
        return expiresAt > Date()
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 20) {
                        formSection("Deal Info") {
                            formField("Title", text: $title, placeholder: "e.g. 20% Off First Order")
                            formField("Description", text: $description, placeholder: "Optional details")
                        }

                        formSection("Discount") {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Type")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)
                                Picker("", selection: $discountType) {
                                    ForEach(DiscountType.allCases) { type in
                                        Text(type.displayName).tag(type)
                                    }
                                }
                                .pickerStyle(.segmented)
                            }

                            if discountType != .bogo {
                                formField(
                                    discountType == .percentage ? "Percentage" : "Amount ($)",
                                    text: $discountValue,
                                    placeholder: discountType == .percentage ? "e.g. 20" : "e.g. 5.00",
                                    keyboard: discountType == .percentage ? .numberPad : .decimalPad
                                )
                            }

                            formField("Min Order (cents, optional)", text: $minOrderAmount, placeholder: "e.g. 2000", keyboard: .numberPad)
                        }

                        formSection("Expiration") {
                            DatePicker("Expires", selection: $expiresAt, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                                .foregroundColor(.keTextPrimary)
                                .tint(.kePrimary)
                            Text("Set the date and time when this deal stops being available.")
                                .font(.caption)
                                .foregroundColor(.keTextMuted)
                        }

                        formSection("Image (optional)") {
                            PhotosPicker(selection: $selectedItem, matching: .images) {
                                if let selectedImage {
                                    Image(uiImage: selectedImage)
                                        .resizable()
                                        .scaledToFill()
                                        .frame(height: 160)
                                        .clipped()
                                        .cornerRadius(10)
                                } else {
                                    HStack {
                                        Image(systemName: "photo.badge.plus")
                                        Text("Select Image")
                                    }
                                    .font(.subheadline)
                                    .foregroundColor(.kePrimary)
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 100)
                                    .background(Color.keCard)
                                    .cornerRadius(10)
                                }
                            }
                        }

                        if let error = localError ?? vm.errorMessage {
                            Text(error)
                                .font(.caption)
                                .foregroundColor(.keError)
                        }

                        Button {
                            Task { await submit() }
                        } label: {
                            Group {
                                if vm.isCreating || isUploading {
                                    ProgressView().progressViewStyle(CircularProgressViewStyle(tint: .white))
                                } else {
                                    Text("Create Deal")
                                        .font(.headline)
                                }
                            }
                            .foregroundColor(.keTextOnAccent)
                            .frame(maxWidth: .infinity)
                            .frame(height: 52)
                            .background(canSubmit ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                            .cornerRadius(12)
                        }
                        .disabled(!canSubmit || vm.isCreating || isUploading)
                    }
                    .padding()
                }
            }
            .navigationTitle("Create Deal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onChange(of: title) { _, _ in
                if title.count > 100 { title = String(title.prefix(100)) }
            }
            .onChange(of: selectedItem) { _, newItem in
                Task {
                    if let data = try? await newItem?.loadTransferable(type: Data.self),
                       let image = UIImage(data: data) {
                        selectedImage = image
                    }
                }
            }
            .onChange(of: vm.createSuccess) { _, success in
                if success {
                    Task {
                        await onCreated()
                        dismiss()
                    }
                }
            }
        }
    }

    private func submit() async {
        localError = nil
        guard !title.isEmpty else {
            localError = "Title is required"
            return
        }

        let value: Int
        if discountType == .bogo {
            value = 0
        } else if discountType == .fixed {
            guard let dollars = Double(discountValue), dollars > 0 else {
                localError = "Enter a valid discount value"
                return
            }
            let cents = Int(round(dollars * 100))
            if cents > 99999 {
                localError = "Fixed discount cannot exceed $999.99"
                return
            }
            value = cents
        } else {
            guard let v = Int(discountValue), v > 0 else {
                localError = "Enter a valid discount value"
                return
            }
            if v > 100 {
                localError = "Percentage discount cannot exceed 100%"
                return
            }
            value = v
        }

        guard expiresAt > Date() else {
            localError = "Expiration must be in the future"
            return
        }

        if let selectedImage {
            isUploading = true
            do {
                imageUrl = try await UploadService.shared.uploadImage(selectedImage, kind: .deal)
            } catch {
                localError = "Image upload failed: \(error.localizedDescription)"
                isUploading = false
                return
            }
            isUploading = false
        }

        let request = CreateDealRequest(
            title: title,
            description: description,
            imageUrl: imageUrl,
            menuItemId: nil,
            discountType: discountType,
            discountValue: value,
            minOrderAmount: Int(minOrderAmount),
            startsAt: nil,
            expiresAt: Self.isoFormatter.string(from: expiresAt)
        )
        await vm.createDeal(request)
    }

    // MARK: - Form Helpers

    private func formSection<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.headline)
                .foregroundColor(.keTextPrimary)
            content()
        }
    }

    private func formField(_ label: String, text: Binding<String>, placeholder: String = "", keyboard: UIKeyboardType = .default) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(.caption)
                .foregroundColor(.keTextSecondary)
            TextField(placeholder, text: text)
                .keyboardType(keyboard)
                .foregroundColor(.keTextPrimary)
                .padding()
                .background(Color.keCard)
                .cornerRadius(10)
        }
    }
}
