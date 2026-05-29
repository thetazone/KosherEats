import SwiftUI
import PhotosUI

struct MenuItemFormView: View {
    let categories: [MenuCategory]
    var existingItem: MenuItem?
    // Args: categoryId, name, description, priceCents, imageUrl, isMeat, isDairy, isPareve
    let onSave: (String, String, String, Int, String, Bool, Bool, Bool) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var description = ""
    @State private var priceText = ""
    @State private var selectedCategoryId = ""
    @State private var isMeat = false
    @State private var isDairy = false
    @State private var isPareve = true

    // Image upload state
    @State private var imageUrl: String = ""
    @State private var pickerItem: PhotosPickerItem?
    @State private var pickedUIImage: UIImage?
    @State private var isUploading = false
    @State private var uploadError: String?

    var isEditing: Bool { existingItem != nil }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 24) {
                        // Photo — tap to pick from library, uploaded via
                        // UploadService to S3 (or stubbed in dev).
                        formSection("Photo") {
                            photoPicker
                        }

                        // Basic Info
                        formSection("Basic Info") {
                            formField("Item Name", text: $name, placeholder: "e.g., Falafel Plate")

                            formField("Description", text: $description, placeholder: "Describe this dish...")

                            VStack(alignment: .leading, spacing: 8) {
                                Text("Price")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)

                                HStack {
                                    Text("$")
                                        .foregroundColor(.keTextMuted)
                                    TextField("0.00", text: $priceText)
                                        .keyboardType(.decimalPad)
                                        .foregroundColor(.keTextPrimary)
                                        .accessibilityLabel("Price in dollars")
                                        .onChange(of: priceText) { _, newValue in
                                            var filtered = newValue.filter { $0.isNumber || $0 == "." }
                                            // Prevent multiple decimal points — keep only the first "."
                                            if filtered.filter({ $0 == "." }).count > 1 {
                                                var seenDot = false
                                                filtered = String(filtered.filter { ch in
                                                    if ch == "." {
                                                        if seenDot { return false }
                                                        seenDot = true
                                                    }
                                                    return true
                                                })
                                            }
                                            if filtered != newValue { priceText = filtered }
                                        }
                                }
                                .padding()
                                .background(Color.keCard)
                                .cornerRadius(12)
                            }
                        }

                        // Category
                        formSection("Category") {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Category")
                                    .font(.caption)
                                    .foregroundColor(.keTextSecondary)

                                ScrollView(.horizontal, showsIndicators: false) {
                                    HStack(spacing: 8) {
                                        ForEach(categories) { cat in
                                            Button {
                                                selectedCategoryId = cat.id
                                                Haptics.selection()
                                            } label: {
                                                Text(cat.name)
                                                    .font(.subheadline)
                                                    .foregroundColor(
                                                        selectedCategoryId == cat.id ? .white : .keTextSecondary
                                                    )
                                                    .padding(.horizontal, 16)
                                                    .padding(.vertical, 10)
                                                    .background(
                                                        selectedCategoryId == cat.id ? Color.kePrimary : Color.keCard
                                                    )
                                                    .cornerRadius(10)
                                            }
                                            .accessibilityLabel("\(cat.name) category")
                                            .accessibilityAddTraits(selectedCategoryId == cat.id ? .isSelected : [])
                                        }
                                    }
                                }
                            }
                        }

                        // Kosher Classification
                        formSection("Kosher Classification") {
                            VStack(spacing: 12) {
                                kosherToggle("Meat", icon: "flame.fill", color: .keError, isOn: $isMeat) {
                                    isDairy = false
                                    isPareve = false
                                }

                                kosherToggle("Dairy", icon: "drop.fill", color: .blue, isOn: $isDairy) {
                                    isMeat = false
                                    isPareve = false
                                }

                                kosherToggle("Pareve", icon: "leaf.fill", color: .keSuccess, isOn: $isPareve) {
                                    isMeat = false
                                    isDairy = false
                                }
                            }
                        }

                        // Modifiers — only available for already-saved items
                        // because modifier groups belong to a menu_item.id that
                        // doesn't exist until after the first save.
                        if let item = existingItem {
                            formSection("Options & Extras") {
                                NavigationLink {
                                    ModifierGroupsEditorView(itemID: item.id, itemName: item.name)
                                } label: {
                                    HStack {
                                        Image(systemName: "slider.horizontal.3")
                                            .foregroundColor(.kePrimary)
                                            .frame(width: 24)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text("Modifier Groups")
                                                .foregroundColor(.keTextPrimary)
                                                .font(.subheadline)
                                            Text("\((item.modifierGroups ?? []).count) group(s)")
                                                .foregroundColor(.keTextMuted)
                                                .font(.caption)
                                        }
                                        Spacer()
                                        Image(systemName: "chevron.right")
                                            .font(.caption)
                                            .foregroundColor(.keTextMuted)
                                    }
                                    .padding()
                                    .background(Color.keCard)
                                    .cornerRadius(12)
                                }
                            }
                        } else {
                            Text("Save this item first, then add options & extras.")
                                .font(.caption)
                                .foregroundColor(.keTextMuted)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }

                        // Save Button
                        Button {
                            // priceText is dollars as typed; convert to cents.
                            let priceCents = Int(round((Double(priceText) ?? 0) * 100))
                            let trimmedName = name.trimmingCharacters(in: .whitespaces)
                            Haptics.impact(.light)
                            onSave(selectedCategoryId, trimmedName, description, priceCents, imageUrl, isMeat, isDairy, isPareve)
                        } label: {
                            Text(isEditing ? "Update Item" : "Add Item")
                                .font(.headline)
                                .foregroundColor(.keTextOnAccent)
                                .frame(maxWidth: .infinity)
                                .frame(height: 52)
                                .background(canSave ? Color.kePrimary : Color.kePrimary.opacity(0.4))
                                .cornerRadius(14)
                        }
                        .disabled(!canSave)
                    }
                    .padding()
                }
            }
            .navigationTitle(isEditing ? "Edit Item" : "New Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .foregroundColor(.kePrimary)
                }
            }
            .onAppear {
                if let item = existingItem {
                    name = item.name
                    description = item.description
                    priceText = String(format: "%.2f", Double(item.price) / 100)
                    selectedCategoryId = item.categoryId
                    isMeat = item.isMeat
                    isDairy = item.isDairy
                    isPareve = item.isPareve
                    imageUrl = item.imageUrl ?? ""
                } else if let first = categories.first {
                    selectedCategoryId = first.id
                }
            }
            .onChange(of: pickerItem) { _, newItem in
                Task { await loadAndUpload(newItem) }
            }
        }
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

    private func formField(_ label: String, text: Binding<String>, placeholder: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption)
                .foregroundColor(.keTextSecondary)

            TextField(placeholder, text: text)
                .foregroundColor(.keTextPrimary)
                .padding()
                .background(Color.keCard)
                .cornerRadius(12)
                .accessibilityLabel(label)
        }
    }

    private func kosherToggle(
        _ label: String,
        icon: String,
        color: Color,
        isOn: Binding<Bool>,
        exclusiveAction: @escaping () -> Void
    ) -> some View {
        Button {
            exclusiveAction()
            isOn.wrappedValue = true
        } label: {
            HStack(spacing: 12) {
                Image(systemName: icon)
                    .foregroundColor(color)
                    .frame(width: 24)

                Text(label)
                    .font(.subheadline)
                    .foregroundColor(.keTextPrimary)

                Spacer()

                Image(systemName: isOn.wrappedValue ? "checkmark.circle.fill" : "circle")
                    .foregroundColor(isOn.wrappedValue ? color : .keTextMuted)
                    .font(.title3)
            }
            .padding()
            .background(isOn.wrappedValue ? color.opacity(0.1) : Color.keCard)
            .cornerRadius(12)
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(isOn.wrappedValue ? color.opacity(0.3) : Color.clear, lineWidth: 1)
            )
        }
        .accessibilityLabel("\(label) kosher classification")
        .accessibilityAddTraits(isOn.wrappedValue ? .isSelected : [])
    }

    private var canSave: Bool {
        let priceCents = Int(round((Double(priceText) ?? 0) * 100))
        return !name.trimmingCharacters(in: .whitespaces).isEmpty &&
            !selectedCategoryId.isEmpty &&
            priceCents > 0 && priceCents <= 999_999 &&
            (isMeat || isDairy || isPareve) &&
            !isUploading &&
            (pickerItem == nil || uploadError == nil)
    }

    // MARK: - Photo picker

    /// Photo tile at the top of the form. Tapping opens PhotosPicker; once a
    /// photo is selected it's uploaded via UploadService and the returned
    /// S3 URL is stashed in `imageUrl` to be sent on save.
    private var photoPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            PhotosPicker(selection: $pickerItem, matching: .images, photoLibrary: .shared()) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(Color.keCard)
                        .frame(height: 180)

                    if let pickedUIImage = pickedUIImage {
                        Image(uiImage: pickedUIImage)
                            .resizable()
                            .scaledToFill()
                            .frame(height: 180)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else if !imageUrl.isEmpty {
                        // Editing an existing item — show the already-saved image.
                        RemoteImage(url: imageUrl)
                            .frame(height: 180)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    } else {
                        VStack(spacing: 8) {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 32))
                                .foregroundColor(.kePrimary)
                            Text("Add photo")
                                .font(.subheadline)
                                .foregroundColor(.keTextSecondary)
                        }
                    }

                    if isUploading {
                        ZStack {
                            Color.black.opacity(0.55)
                            ProgressView()
                                .progressViewStyle(CircularProgressViewStyle(tint: .white))
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .disabled(isUploading)
            .accessibilityLabel(pickedUIImage != nil ? "Change item photo" : "Add item photo")
            .accessibilityHint("Opens the photo picker")

            if let err = uploadError {
                Text(err).font(.caption2).foregroundColor(.keError)
            }
        }
    }

    /// Loads the selected photo, compresses to JPEG, uploads via UploadService,
    /// and stores the resulting public URL. Errors are surfaced inline so the
    /// seller knows the image won't save.
    private func loadAndUpload(_ item: PhotosPickerItem?) async {
        guard let item = item else { return }
        isUploading = true
        uploadError = nil
        defer { isUploading = false }

        do {
            guard let data = try await item.loadTransferable(type: Data.self),
                  let uiImage = UIImage(data: data) else {
                throw NSError(domain: "photo", code: 0, userInfo: [NSLocalizedDescriptionKey: "Couldn't read photo"])
            }
            pickedUIImage = uiImage
            let publicURL = try await UploadService.shared.uploadImage(uiImage, kind: .menuItem)
            imageUrl = publicURL
            Haptics.success()
        } catch {
            uploadError = error.localizedDescription
            Haptics.error()
        }
    }
}
