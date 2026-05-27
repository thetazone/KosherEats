import Foundation

// URLs required by Apple App Store Review (guideline 5.1.1): both pages must
// be publicly reachable HTTPS before submission. Update in one place — these
// constants are consumed by ProfileView, sign-up consent copy, etc.
enum LegalURLs {
    static let privacyPolicy = URL(string: "https://greeneats.com/privacy")!
    static let termsOfService = URL(string: "https://greeneats.com/terms")!
    static let supportEmail = URL(string: "mailto:support@greeneats.com")!
}
