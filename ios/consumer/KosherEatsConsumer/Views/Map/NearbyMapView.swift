import SwiftUI
import MapKit

struct NearbyMapView: View {
    @StateObject private var vm = HomeViewModel()
    @State private var position: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var selectedRestaurant: Restaurant?

    var body: some View {
        NavigationStack {
            ZStack {
                Map(position: $position) {
                    UserAnnotation()

                    ForEach(vm.filteredRestaurants) { restaurant in
                        Annotation(
                            restaurant.name,
                            coordinate: CLLocationCoordinate2D(
                                latitude: restaurant.lat,
                                longitude: restaurant.lng
                            )
                        ) {
                            RestaurantMapPin(restaurant: restaurant)
                                .onTapGesture {
                                    selectedRestaurant = restaurant
                                }
                        }
                    }
                }
                .mapStyle(.standard(pointsOfInterest: .excludingAll))
                .mapControls {
                    MapUserLocationButton()
                    MapCompass()
                }
                .ignoresSafeArea(edges: .top)

                // Bottom card when a restaurant is selected
                if let restaurant = selectedRestaurant {
                    VStack {
                        Spacer()
                        NavigationLink(destination: RestaurantDetailView(restaurantID: restaurant.id)) {
                            SelectedRestaurantCard(restaurant: restaurant)
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal)
                        .padding(.bottom, 8)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                    .animation(.spring(response: 0.3), value: selectedRestaurant?.id)
                }
            }
            .navigationTitle("Nearby")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        position = .userLocation(fallback: .automatic)
                    } label: {
                        Image(systemName: "location.fill")
                            .foregroundColor(.kePrimary)
                    }
                }
            }
        }
        .task {
            await vm.loadRestaurants()
        }
    }
}

// MARK: - Map Pin

struct RestaurantMapPin: View {
    let restaurant: Restaurant

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 4) {
                Image(systemName: "fork.knife")
                    .font(.system(size: 10, weight: .bold))
                Text(String(format: "%.1f", restaurant.rating))
                    .font(.system(size: 12, weight: .bold))
            }
            .foregroundColor(restaurant.isOpen ? .white : .gray)
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(
                restaurant.isOpen
                    ? Color.keBackgroundElevated
                    : Color.keCard
            )
            .cornerRadius(12)
            .shadow(color: .black.opacity(0.3), radius: 4, y: 2)

            Text(restaurant.name)
                .font(.system(size: 10, weight: .semibold))
                .foregroundColor(.white)
                .lineLimit(1)
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .background(Color.black.opacity(0.6))
                .cornerRadius(4)
        }
    }
}

// MARK: - Selected Restaurant Card

struct SelectedRestaurantCard: View {
    let restaurant: Restaurant

    var body: some View {
        HStack(spacing: 14) {
            // Restaurant image
            AsyncImage(url: URL(string: restaurant.imageURL)) { image in
                image
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } placeholder: {
                Color.keCard
            }
            .frame(width: 72, height: 72)
            .cornerRadius(12)

            VStack(alignment: .leading, spacing: 4) {
                Text(restaurant.name)
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(.keTextPrimary)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    // Rating
                    HStack(spacing: 2) {
                        Image(systemName: "star.fill")
                            .font(.system(size: 11))
                            .foregroundColor(.kePrimary)
                        Text(String(format: "%.1f", restaurant.rating))
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.keTextPrimary)
                    }

                    Text("·")
                        .foregroundColor(.keTextMuted)

                    // Certification badge
                    Text(restaurant.kosherCertification.displayName)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(.kePrimary)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.kePrimary.opacity(0.15))
                        .cornerRadius(4)

                    if restaurant.isGlattKosher {
                        Text("Glatt")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.keTextTertiary)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.keCard)
                            .cornerRadius(4)
                    }
                }

                HStack(spacing: 8) {
                    Text("\(restaurant.estDeliveryMin)-\(restaurant.estDeliveryMax) min")
                        .font(.system(size: 12))
                        .foregroundColor(.keTextTertiary)
                    Text("·")
                        .foregroundColor(.keTextMuted)
                    Text(restaurant.deliveryFeeFormatted)
                        .font(.system(size: 12))
                        .foregroundColor(.keTextTertiary)
                }
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.system(size: 14, weight: .semibold))
                .foregroundColor(.keTextMuted)
        }
        .padding(14)
        .background(Color.keBackgroundElevated)
        .cornerRadius(16)
        .shadow(color: .black.opacity(0.4), radius: 12, y: 4)
    }
}
