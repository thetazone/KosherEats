import Foundation

// URLs required by Apple App Store Review (guideline 5.1.1): both pages must
// be publicly reachable HTTPS before submission. Same policy can back all
// three apps — update in one place when the canonical URLs are published.
enum LegalURLs {
    static let privacyPolicy = URL(string: "https://koshereats.com/privacy")!
    static let termsOfService = URL(string: "https://koshereats.com/terms")!
    static let supportEmail = URL(string: "mailto:couriers@koshereats.com")!
}
