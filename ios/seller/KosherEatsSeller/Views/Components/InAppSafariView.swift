import SafariServices
import SwiftUI

/// SFSafariViewController wrapper for hosted flows that must render on their
/// own domain — Stripe Connect onboarding and the Restaurant Partner
/// Agreement. Present it in a `.sheet`; the sheet's `onDismiss` is the hook
/// for re-fetching state after the seller taps Done.
struct InAppSafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let controller = SFSafariViewController(url: url)
        controller.preferredControlTintColor = UIColor(Color.kePrimary)
        controller.dismissButtonStyle = .done
        return controller
    }

    func updateUIViewController(_ uiViewController: SFSafariViewController, context: Context) {}
}

/// Identifiable URL so callers can drive `.sheet(item:)` — avoids the
/// `.sheet(isPresented:)` race where the URL state isn't set yet on the first
/// render and the sheet comes up blank.
struct SafariDestination: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
