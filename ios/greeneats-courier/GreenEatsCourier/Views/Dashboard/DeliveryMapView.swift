import SwiftUI
import MapKit
import CoreLocation
import UIKit

// DeliveryMapView is the in-app navigation surface shown while a courier has
// an active delivery. Modeled after the Uber Driver map: full-bleed MapKit
// with a bottom action card, phase-keyed on order.status (ready -> pickup,
// picked_up -> dropoff). MKDirections powers the route polyline + ETA;
// the Navigate button hands off to Apple Maps / Google Maps / Waze.
struct DeliveryMapView: View {
    let order: CourierOrder
    @ObservedObject var vm: DashboardViewModel
    @ObservedObject var location: LocationManager

    @State private var cameraPosition: MapCameraPosition = .automatic
    @State private var route: MKRoute?
    @State private var showNavigateSheet: Bool = false
    @State private var showDeliveryProofCamera = false
    @State private var showCameraSheet = false
    @State private var deliveryProofImage: UIImage?
    @State private var uploadCandidate: UIImage?
    @State private var isUploading = false
    @State private var uploadError: String?
    @State private var showCancellationAlert = false
    @State private var cancellationAlertTriggered = false
    @State private var didDeliver = false
    @State private var uploadTask: Task<Void, Never>?
    @State private var isFetchingRoute = false
    @Environment(\.dismiss) private var dismiss

    /// Live order looked up from the ViewModel's active array so the view
    /// reacts to status changes pushed by polling. The passed-in `order` is
    /// only used as a stable ID for the lookup; if it disappears from
    /// `vm.active` we fall back to the original snapshot.
    private var liveOrder: CourierOrder {
        vm.active.first(where: { $0.id == order.id }) ?? order
    }

    private var destinationCoordinate: CLLocationCoordinate2D {
        switch liveOrder.status {
        case "picked_up":
            return CLLocationCoordinate2D(latitude: liveOrder.deliveryLat, longitude: liveOrder.deliveryLng)
        case "ready":
            return CLLocationCoordinate2D(latitude: liveOrder.restaurantLat, longitude: liveOrder.restaurantLng)
        default:
            return CLLocationCoordinate2D(latitude: liveOrder.restaurantLat, longitude: liveOrder.restaurantLng)
        }
    }

    private var destinationLabel: String {
        liveOrder.status == "picked_up" ? "Customer" : liveOrder.restaurantName
    }

    private var phaseHeader: String {
        liveOrder.status == "picked_up" ? "Delivering to customer" : "Heading to pickup"
    }

    private var phaseLabel: String {
        liveOrder.status == "picked_up" ? "On the way to customer" : "Pickup"
    }

    private var actionLabel: String {
        liveOrder.status == "picked_up" ? "Mark delivered" : "I've picked it up"
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            Map(position: $cameraPosition) {
                UserAnnotation()

                Marker(destinationLabel, systemImage: liveOrder.status == "picked_up" ? "house.fill" : "fork.knife",
                       coordinate: destinationCoordinate)
                    .tint(Color.kePrimary)

                if let polyline = route?.polyline {
                    MapPolyline(polyline)
                        .stroke(Color.kePrimary, lineWidth: 5)
                }
            }
            .mapStyle(.standard(elevation: .realistic))
            .ignoresSafeArea(edges: [.top, .horizontal])
            .overlay(alignment: .top) {
                if isFetchingRoute {
                    ProgressView()
                        .padding(8)
                        .background(.thinMaterial)
                        .clipShape(Circle())
                        .padding(.top, 60)
                }
            }

            bottomCard
                .padding(Theme.spacingMD)
        }
        .background(Color.keBackground.ignoresSafeArea())
        .task(id: liveOrder.status) {
            guard liveOrder.status == "ready" || liveOrder.status == "picked_up" else {
                if !cancellationAlertTriggered && !didDeliver {
                    cancellationAlertTriggered = true
                    showCancellationAlert = true
                }
                return
            }
            await fetchRoute()
            fitCamera()
        }
        .confirmationDialog("Navigate with", isPresented: $showNavigateSheet, titleVisibility: .visible) {
            Button("Apple Maps") { openInAppleMaps() }
            if canOpenGoogleMaps {
                Button("Google Maps") { openInGoogleMaps() }
            }
            if canOpenWaze {
                Button("Waze") { openInWaze() }
            }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Delivery proof", isPresented: $showDeliveryProofCamera, titleVisibility: .visible) {
            Button("Take Photo") {
                if UIImagePickerController.isSourceTypeAvailable(.camera) {
                    showCameraSheet = true
                } else {
                    // No camera available — fall through to deliver without proof
                    Task {
                        didDeliver = true
                        await vm.deliver(liveOrder)
                        if vm.errorMessage != nil { didDeliver = false }
                    }
                }
            }
            Button("Skip & Deliver") {
                Task {
                    await vm.deliver(liveOrder)
                    if !vm.active.contains(where: { $0.id == order.id }) { didDeliver = true }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Take a photo as proof of delivery?")
        }
        .sheet(isPresented: $showCameraSheet) {
            CameraView(image: $deliveryProofImage)
                .presentationDetents([.large])
        }
        .onChange(of: deliveryProofImage) { _, newImage in
            guard let image = newImage else { return }
            uploadCandidate = image
            uploadTask?.cancel()
            uploadTask = Task {
                isUploading = true
                defer { isUploading = false }
                do {
                    let proofURL = try await uploadImageWithTimeout(image)
                    didDeliver = true
                    await vm.deliver(liveOrder, proofURL: proofURL)
                    if vm.errorMessage != nil { didDeliver = false }
                } catch is UploadTimedOut {
                    guard !Task.isCancelled else { return }
                    uploadError = "Photo upload timed out. You can retry or skip."
                } catch {
                    guard !Task.isCancelled else { return }
                    uploadError = error.localizedDescription
                }
            }
        }
        .alert("Upload Failed", isPresented: Binding(
            get: { uploadError != nil },
            set: { if !$0 { uploadError = nil } }
        )) {
            Button("Retry") {
                guard let image = uploadCandidate else { return }
                uploadError = nil
                Task {
                    isUploading = true
                    defer { isUploading = false }
                    do {
                        let proofURL = try await uploadImageWithTimeout(image)
                        didDeliver = true
                        await vm.deliver(liveOrder, proofURL: proofURL)
                        if vm.errorMessage != nil { didDeliver = false }
                    } catch is UploadTimedOut {
                        uploadError = "Photo upload timed out. You can retry or skip."
                    } catch {
                        uploadError = error.localizedDescription
                    }
                }
            }
            Button("Skip & Deliver") {
                uploadError = nil
                Task {
                    await vm.deliver(liveOrder)
                    if !vm.active.contains(where: { $0.id == order.id }) { didDeliver = true }
                }
            }
            Button("Cancel", role: .cancel) {
                uploadError = nil
                deliveryProofImage = nil
                uploadCandidate = nil
            }
        } message: {
            Text(uploadError ?? "Could not upload delivery photo. You can retry or skip.")
        }
        .onChange(of: vm.active.map { $0.id }) { _, ids in
            if !ids.contains(order.id) && !didDeliver {
                showCancellationAlert = true
            }
        }
        .alert("Order Cancelled", isPresented: $showCancellationAlert) {
            Button("OK") { dismiss() }
        } message: {
            Text("This order is no longer active.")
        }
        .alert("Action Failed", isPresented: Binding(
            get: { vm.errorMessage != nil },
            set: { if !$0 { vm.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(vm.errorMessage ?? "")
        }
        .onDisappear { uploadTask?.cancel() }
    }

    private var bottomCard: some View {
        VStack(alignment: .leading, spacing: Theme.spacingSM) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(phaseHeader)
                        .font(.caption.bold())
                        .foregroundColor(.kePrimary)
                        .textCase(.uppercase)
                    if let eta = etaText {
                        Text(eta)
                            .font(.title3.bold())
                            .foregroundColor(.keTextPrimary)
                    }
                }
                Spacer()
                Button {
                    showNavigateSheet = true
                } label: {
                    Image(systemName: "arrow.triangle.turn.up.right.diamond.fill")
                        .font(.title3)
                        .foregroundColor(.kePrimary)
                        .frame(width: 44, height: 44)
                        .background(Color.keBackgroundElevated)
                        .cornerRadius(Theme.cornerRadiusMedium)
                }
                .accessibilityLabel("Open navigation")
            }

            VStack(alignment: .leading, spacing: Theme.spacingSM) {
                Label {
                    Text(liveOrder.restaurantName)
                        .foregroundColor(.keTextPrimary)
                        .lineLimit(1)
                } icon: {
                    Image(systemName: "fork.knife").foregroundColor(.kePrimary)
                }

                Label {
                    Text(liveOrder.deliveryAddress)
                        .foregroundColor(.keTextSecondary)
                        .lineLimit(1)
                } icon: {
                    Image(systemName: "house.fill").foregroundColor(.kePrimary)
                }
            }

            HStack(spacing: Theme.spacingSM) {
                NavigationLink(destination: OrderChatView(orderID: liveOrder.id)) {
                    Image(systemName: "bubble.left.fill")
                        .foregroundColor(.kePrimary)
                        .frame(width: 52, height: 52)
                        .background(Color.keBackgroundElevated)
                        .cornerRadius(Theme.cornerRadiusMedium)
                }

                if let phone = liveOrder.customerPhone, !phone.isEmpty,
                   let encoded = phone.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
                   let telURL = URL(string: "tel:\(encoded)") {
                    Link(destination: telURL) {
                        Image(systemName: "phone.fill")
                            .foregroundColor(.kePrimary)
                            .frame(width: 52, height: 52)
                            .background(Color.keBackgroundElevated)
                            .cornerRadius(Theme.cornerRadiusMedium)
                    }
                }

                Button(actionLabel) {
                    Task {
                        if liveOrder.status == "picked_up" {
                            showDeliveryProofCamera = true
                        } else {
                            await vm.pickup(liveOrder)
                        }
                    }
                }
                .buttonStyle(KEPrimaryButtonStyle())
            }
        }
        .padding()
        .background(Color.keCard)
        .cornerRadius(Theme.cornerRadiusMedium)
        .shadow(color: .black.opacity(0.25), radius: 12, y: 4)
    }

    private var etaText: String? {
        guard let route else { return nil }
        let minutes = Int((route.expectedTravelTime / 60).rounded())
        let miles = route.distance / 1609.34
        // Sanity check: if the route is implausibly long, the courier's
        // location is almost certainly wrong (e.g., simulator default at
        // Cupertino computing a route to an NJ restaurant). Showing
        // "2436 min · 2928 mi" is worse than showing nothing.
        if miles > 50 || minutes > 120 { return nil }
        return String(format: "%d min · %.1f mi", minutes, miles)
    }

    // MARK: - Directions

    private func fetchRoute() async {
        guard let me = location.currentLocation else {
            route = nil
            return
        }
        let request = MKDirections.Request()
        request.source = MKMapItem(placemark: MKPlacemark(coordinate: me.coordinate))
        request.destination = MKMapItem(placemark: MKPlacemark(coordinate: destinationCoordinate))
        request.transportType = .automobile
        let directions = MKDirections(request: request)
        isFetchingRoute = true
        defer { isFetchingRoute = false }
        do {
            let fetched: MKRoute? = try await withThrowingTaskGroup(of: MKRoute?.self) { group in
                group.addTask { try await directions.calculate().routes.first }
                group.addTask {
                    try await Task.sleep(for: .seconds(10))
                    return nil
                }
                let result = try await group.next() ?? nil
                group.cancelAll()
                return result
            }
            route = fetched
        } catch {
            route = nil
        }
    }

    private func fitCamera() {
        guard let me = location.currentLocation else {
            cameraPosition = .region(MKCoordinateRegion(
                center: destinationCoordinate,
                latitudinalMeters: 2000,
                longitudinalMeters: 2000
            ))
            return
        }
        let dest = destinationCoordinate
        let minLat = min(me.coordinate.latitude, dest.latitude)
        let maxLat = max(me.coordinate.latitude, dest.latitude)
        let minLng = min(me.coordinate.longitude, dest.longitude)
        let maxLng = max(me.coordinate.longitude, dest.longitude)
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLng + maxLng) / 2
        )
        let span = MKCoordinateSpan(
            latitudeDelta: max((maxLat - minLat) * 1.6, 0.01),
            longitudeDelta: max((maxLng - minLng) * 1.6, 0.01)
        )
        cameraPosition = .region(MKCoordinateRegion(center: center, span: span))
    }

    // MARK: - Navigation handoff

    private var canOpenGoogleMaps: Bool {
        guard let url = URL(string: "comgooglemaps://") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    private var canOpenWaze: Bool {
        guard let url = URL(string: "waze://") else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    private func openInAppleMaps() {
        let placemark = MKPlacemark(coordinate: destinationCoordinate)
        let item = MKMapItem(placemark: placemark)
        item.name = destinationLabel
        item.openInMaps(launchOptions: [
            MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeDriving
        ])
    }

    private func openInGoogleMaps() {
        let lat = destinationCoordinate.latitude
        let lng = destinationCoordinate.longitude
        guard let url = URL(string: "comgooglemaps://?daddr=\(lat),\(lng)&directionsmode=driving") else { return }
        UIApplication.shared.open(url)
    }

    private func openInWaze() {
        let lat = destinationCoordinate.latitude
        let lng = destinationCoordinate.longitude
        guard let url = URL(string: "waze://?ll=\(lat),\(lng)&navigate=yes") else { return }
        UIApplication.shared.open(url)
    }

    private func uploadImageWithTimeout(_ image: UIImage) async throws -> String {
        try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask { try await UploadService.shared.uploadImage(image, kind: .deliveryProof) }
            group.addTask {
                try await Task.sleep(nanoseconds: 30_000_000_000)
                throw UploadTimedOut()
            }
            defer { group.cancelAll() }
            guard let result = try await group.next() else { throw UploadTimedOut() }
            return result
        }
    }
}

private struct UploadTimedOut: Error {}

// MARK: - Camera representable for delivery proof

struct CameraView: UIViewControllerRepresentable {
    @Binding var image: UIImage?
    @Environment(\.dismiss) var dismiss

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let parent: CameraView
        init(_ parent: CameraView) { self.parent = parent }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            parent.image = info[.originalImage] as? UIImage
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
