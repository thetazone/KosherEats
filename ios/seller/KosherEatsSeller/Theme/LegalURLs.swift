import Foundation

// URLs required by Apple App Store Review (guideline 5.1.1): both pages must
// be publicly reachable HTTPS before submission. Seller and consumer can share
// the same policy — update here when the canonical URLs land.
enum LegalURLs {
    static let privacyPolicy = safeURL("https://koshereats.com/privacy")
    static let termsOfService = safeURL("https://koshereats.com/terms")
    static let supportEmail = safeURL("mailto:sellers@koshereats.com")

    private static func safeURL(_ string: String) -> URL {
        guard let url = URL(string: string) else {
            assertionFailure("Invalid hardcoded URL: \(string)")
            // Fallback: return an about:blank URL which is always valid.
            return URL(string: "about:blank")! // swiftlint:disable:this force_unwrapping
        }
        return url
    }
}
