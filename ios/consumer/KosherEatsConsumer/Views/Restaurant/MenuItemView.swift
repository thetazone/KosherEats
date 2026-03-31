import SwiftUI

struct MenuItemView: View {
    let item: MenuItem
    let restaurantID: String
    @EnvironmentObject var cartVM: CartViewModel
    @State private var showAddSheet = false

    var body: some View {
        Button {
            if item.isAvailable {
                showAddSheet = true
            }
        } label: {
            HStack(spacing: 14) {
                // Item image placeholder
                ZStack {
                    RoundedRectangle(cornerRadius: Theme.cornerRadiusSmall)
                        .fill(Color.keCardHover)
                        .frame(width: 72, height: 72)

                    Image(systemName: "takeoutbag.and.cup.and.straw")
                        .font(.system(size: 24))
                        .foregroundColor(.keTextMuted.opacity(0.5))
                }

                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(item.name)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundColor(item.isAvailable ? .keTextPrimary : .keTextMuted)
                            .lineLimit(1)

                        Spacer()

                        // Kashrus type indicator
                        if item.isMeat {
                            KashrusTypeIndicator(type: "M", color: .keMeat)
                        } else if item.isDairy {
                            KashrusTypeIndicator(type: "D", color: .keDairy)
                        } else if item.isPareve {
                            KashrusTypeIndicator(type: "P", color: .kePareve)
                        }
                    }

                    if !item.description.isEmpty {
                        Text(item.description)
                            .font(.system(size: 13))
                            .foregroundColor(.keTextTertiary)
                            .lineLimit(2)
                    }

                    HStack {
                        Text(item.priceFormatted)
                            .font(.system(size: 15, weight: .bold))
                            .foregroundColor(.kePrimary)

                        Spacer()

                        if !item.isAvailable {
                            Text("Unavailable")
                                .font(.system(size: 12, weight: .medium))
                                .foregroundColor(.keError)
                        } else {
                            Image(systemName: "plus.circle.fill")
                                .font(.system(size: 24))
                                .foregroundColor(.kePrimary)
                        }
                    }
                }
            }
            .padding(12)
            .background(Color.keCard)
            .cornerRadius(Theme.cornerRadiusMedium)
            .opacity(item.isAvailable ? 1.0 : 0.6)
        }
        .buttonStyle(.plain)
        .disabled(!item.isAvailable)
        .sheet(isPresented: $showAddSheet) {
            AddToCartSheet(item: item, restaurantID: restaurantID)
        }
    }
}

// MARK: - Kashrus Type Indicator

struct KashrusTypeIndicator: View {
    let type: String
    let color: Color

    var body: some View {
        Text(type)
            .font(.system(size: 11, weight: .heavy))
            .foregroundColor(.white)
            .frame(width: 22, height: 22)
            .background(color)
            .cornerRadius(6)
    }
}

// MARK: - Add to Cart Sheet

struct AddToCartSheet: View {
    let item: MenuItem
    let restaurantID: String
    @EnvironmentObject var cartVM: CartViewModel
    @Environment(\.dismiss) var dismiss
    @State private var quantity = 1
    @State private var notes = ""

    private var totalPrice: String {
        "$\(String(format: "%.2f", Double(item.price * quantity) / 100))"
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.keBackground.ignoresSafeArea()

                VStack(spacing: Theme.spacingLG) {
                    // Item info
                    VStack(spacing: 8) {
                        ZStack {
                            RoundedRectangle(cornerRadius: Theme.cornerRadiusMedium)
                                .fill(Color.keCardHover)
                                .frame(height: 160)

                            Image(systemName: "takeoutbag.and.cup.and.straw")
                                .font(.system(size: 48))
                                .foregroundColor(.keTextMuted.opacity(0.3))
                        }

                        Text(item.name)
                            .font(.system(size: 22, weight: .bold))
                            .foregroundColor(.keTextPrimary)

                        Text(item.description)
                            .font(.body)
                            .foregroundColor(.keTextSecondary)
                            .multilineTextAlignment(.center)

                        HStack(spacing: 8) {
                            Text(item.priceFormatted)
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.kePrimary)

                            if item.isMeat {
                                KashrusTag(text: "Meat", color: .keMeat)
                            } else if item.isDairy {
                                KashrusTag(text: "Dairy", color: .keDairy)
                            } else if item.isPareve {
                                KashrusTag(text: "Pareve", color: .kePareve)
                            }
                        }
                    }
                    .padding(.horizontal)

                    // Notes
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Special Instructions")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.keTextSecondary)

                        TextField("e.g., no onions, extra sauce", text: $notes)
                            .keTextField()
                    }
                    .padding(.horizontal)

                    Spacer()

                    // Quantity + Add button
                    VStack(spacing: 16) {
                        // Quantity stepper
                        HStack(spacing: 24) {
                            Button {
                                if quantity > 1 { quantity -= 1 }
                            } label: {
                                Image(systemName: "minus.circle.fill")
                                    .font(.system(size: 36))
                                    .foregroundColor(quantity > 1 ? .kePrimary : .keTextMuted)
                            }
                            .disabled(quantity <= 1)

                            Text("\(quantity)")
                                .font(.system(size: 24, weight: .bold))
                                .foregroundColor(.keTextPrimary)
                                .frame(width: 40)

                            Button {
                                if quantity < 99 { quantity += 1 }
                            } label: {
                                Image(systemName: "plus.circle.fill")
                                    .font(.system(size: 36))
                                    .foregroundColor(.kePrimary)
                            }
                        }

                        Button {
                            Task {
                                await cartVM.addItem(
                                    menuItemID: item.id,
                                    quantity: quantity,
                                    notes: notes.isEmpty ? nil : notes,
                                    restaurantID: restaurantID
                                )
                                dismiss()
                            }
                        } label: {
                            HStack {
                                Text("Add to Cart")
                                Spacer()
                                Text(totalPrice)
                            }
                        }
                        .buttonStyle(KEPrimaryButtonStyle())
                        .padding(.horizontal)
                    }
                    .padding(.bottom, Theme.spacingLG)
                }
            }
            .navigationTitle("Add Item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Close") { dismiss() }
                        .foregroundColor(.kePrimary)
                }
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
    }
}
