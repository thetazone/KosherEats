import Foundation

// URLs required by Apple App Store Review (guideline 5.1.1): both pages must
// be publicly reachable HTTPS before submission. Seller and consumer can share
// the same policy — update here when the canonical URLs land.
enum LegalURLs {
    // swiftlint:disable force_unwrapping — compile-time constants, always valid
    static let privacyPolicy = URL(string: "https://greeneats.com/privacy")!
    static let termsOfService = URL(string: "https://greeneats.com/terms")!
    static let supportEmail = URL(string: "mailto:sellers@greeneats.com")!
    // swiftlint:enable force_unwrapping
}
