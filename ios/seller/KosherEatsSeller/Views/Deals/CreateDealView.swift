import SwiftUI
import PhotosUI

struct CreateDealView: View {
    let onCreated: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var vm = DealsViewModel()

    @State private var title = ""
    @State private var description = ""
    @State private var discountType: DiscountType = .percentage
    @State private var discountValue = ""
    @State private var minOrderAmount = ""
    @State private var expiresAt = Date()
    @State private var selectedItem: PhotosPickerItem?
    @State private var selectedImage: UIImage?
    @State private var imageUrl = ""
    @State private var isUploading = false
    @State private var localError: String?

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
                                    discountType == .percentage ? "Percentage" : "Amount (cents)",
                                    text: $discountValue,
                                    placeholder: discountType == .percentage ? "e.g. 20" : "e.g. 500",
                                    keyboard: .numberPad
                                )
                            }

                            formField("Min Order (cents, optional)", text: $minOrderAmount, placeholder: "e.g. 2000", keyboard: .numberPad)
                        }

                        formSection("Expiration") {
                            DatePicker("Expires", selection: $expiresAt, in: Date()..., displayedComponents: .date)
                                .foregroundColor(.keTextPrimary)
                                .tint(.kePrimary)
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
                            .background(Color.kePrimary)
                            .cornerRadius(12)
                        }
                        .disabled(vm.isCreating || isUploading)
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
        } else {
            guard let v = Int(discountValue), v > 0 else {
                localError = "Enter a valid discount value"
                return
            }
            value = v
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

        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]

        let request = CreateDealRequest(
            title: title,
            description: description,
            imageUrl: imageUrl,
            menuItemId: nil,
            discountType: discountType,
            discountValue: value,
            minOrderAmount: Int(minOrderAmount),
            startsAt: nil,
            expiresAt: formatter.string(from: expiresAt)
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
