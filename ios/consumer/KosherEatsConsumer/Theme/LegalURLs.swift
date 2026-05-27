import Foundation

// URLs required by Apple App Store Review (guideline 5.1.1): both pages must
// be publicly reachable HTTPS before submission. Update in one place — these
// constants are consumed by ProfileView, sign-up consent copy, etc.
enum LegalURLs {
    // swiftlint:disable:next force_unwrapping
    private static let fallbackURL = URL(string: "about:blank")!

    static let privacyPolicy = URL(string: "https://koshereats.com/privacy") ?? fallbackURL
    static let termsOfService = URL(string: "https://koshereats.com/terms") ?? fallbackURL
    static let supportEmail = URL(string: "mailto:support@koshereats.com") ?? fallbackURL
}
