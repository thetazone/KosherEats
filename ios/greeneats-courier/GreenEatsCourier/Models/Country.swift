import Foundation

/// Dial-code entry for the phone-login country picker. Kept as a plain struct
/// so the picker sheet can filter + render directly without a view model.
struct Country: Identifiable, Hashable {
    let iso: String       // ISO-3166-1 alpha-2, e.g. "US"
    let name: String
    let flag: String
    let dialCode: String  // leading "+" included
    var id: String { iso }
}

extension Country {
    /// Default fallback: United States. Phone-login entry defaults to this so
    /// first-time US users don't have to hunt through the picker.
    static let defaultCountry = Country(iso: "US", name: "United States", flag: "🇺🇸", dialCode: "+1")

    /// Curated worldwide list sorted by country name. Covers all major markets
    /// plus the Jewish-diaspora hubs the backend is likely to see (IL, FR, AR,
    /// MX, ZA, etc.). Add entries here as we expand; the picker's search field
    /// handles discoverability.
    static let all: [Country] = [
        Country(iso: "AR", name: "Argentina",       flag: "🇦🇷", dialCode: "+54"),
        Country(iso: "AU", name: "Australia",       flag: "🇦🇺", dialCode: "+61"),
        Country(iso: "AT", name: "Austria",         flag: "🇦🇹", dialCode: "+43"),
        Country(iso: "BH", name: "Bahrain",         flag: "🇧🇭", dialCode: "+973"),
        Country(iso: "BE", name: "Belgium",         flag: "🇧🇪", dialCode: "+32"),
        Country(iso: "BO", name: "Bolivia",         flag: "🇧🇴", dialCode: "+591"),
        Country(iso: "BR", name: "Brazil",          flag: "🇧🇷", dialCode: "+55"),
        Country(iso: "BG", name: "Bulgaria",        flag: "🇧🇬", dialCode: "+359"),
        Country(iso: "CA", name: "Canada",          flag: "🇨🇦", dialCode: "+1"),
        Country(iso: "CL", name: "Chile",           flag: "🇨🇱", dialCode: "+56"),
        Country(iso: "CN", name: "China",           flag: "🇨🇳", dialCode: "+86"),
        Country(iso: "CO", name: "Colombia",        flag: "🇨🇴", dialCode: "+57"),
        Country(iso: "CR", name: "Costa Rica",      flag: "🇨🇷", dialCode: "+506"),
        Country(iso: "HR", name: "Croatia",         flag: "🇭🇷", dialCode: "+385"),
        Country(iso: "CY", name: "Cyprus",          flag: "🇨🇾", dialCode: "+357"),
        Country(iso: "CZ", name: "Czech Republic",  flag: "🇨🇿", dialCode: "+420"),
        Country(iso: "DK", name: "Denmark",         flag: "🇩🇰", dialCode: "+45"),
        Country(iso: "DO", name: "Dominican Republic", flag: "🇩🇴", dialCode: "+1"),
        Country(iso: "EC", name: "Ecuador",         flag: "🇪🇨", dialCode: "+593"),
        Country(iso: "EG", name: "Egypt",           flag: "🇪🇬", dialCode: "+20"),
        Country(iso: "SV", name: "El Salvador",     flag: "🇸🇻", dialCode: "+503"),
        Country(iso: "EE", name: "Estonia",         flag: "🇪🇪", dialCode: "+372"),
        Country(iso: "FI", name: "Finland",         flag: "🇫🇮", dialCode: "+358"),
        Country(iso: "FR", name: "France",          flag: "🇫🇷", dialCode: "+33"),
        Country(iso: "GE", name: "Georgia",         flag: "🇬🇪", dialCode: "+995"),
        Country(iso: "DE", name: "Germany",         flag: "🇩🇪", dialCode: "+49"),
        Country(iso: "GR", name: "Greece",          flag: "🇬🇷", dialCode: "+30"),
        Country(iso: "GT", name: "Guatemala",       flag: "🇬🇹", dialCode: "+502"),
        Country(iso: "HN", name: "Honduras",        flag: "🇭🇳", dialCode: "+504"),
        Country(iso: "HK", name: "Hong Kong",       flag: "🇭🇰", dialCode: "+852"),
        Country(iso: "HU", name: "Hungary",         flag: "🇭🇺", dialCode: "+36"),
        Country(iso: "IS", name: "Iceland",         flag: "🇮🇸", dialCode: "+354"),
        Country(iso: "IN", name: "India",           flag: "🇮🇳", dialCode: "+91"),
        Country(iso: "ID", name: "Indonesia",       flag: "🇮🇩", dialCode: "+62"),
        Country(iso: "IE", name: "Ireland",         flag: "🇮🇪", dialCode: "+353"),
        Country(iso: "IL", name: "Israel",          flag: "🇮🇱", dialCode: "+972"),
        Country(iso: "IT", name: "Italy",           flag: "🇮🇹", dialCode: "+39"),
        Country(iso: "JM", name: "Jamaica",         flag: "🇯🇲", dialCode: "+1"),
        Country(iso: "JP", name: "Japan",           flag: "🇯🇵", dialCode: "+81"),
        Country(iso: "JO", name: "Jordan",          flag: "🇯🇴", dialCode: "+962"),
        Country(iso: "KZ", name: "Kazakhstan",      flag: "🇰🇿", dialCode: "+7"),
        Country(iso: "KE", name: "Kenya",           flag: "🇰🇪", dialCode: "+254"),
        Country(iso: "KW", name: "Kuwait",          flag: "🇰🇼", dialCode: "+965"),
        Country(iso: "LV", name: "Latvia",          flag: "🇱🇻", dialCode: "+371"),
        Country(iso: "LB", name: "Lebanon",         flag: "🇱🇧", dialCode: "+961"),
        Country(iso: "LT", name: "Lithuania",       flag: "🇱🇹", dialCode: "+370"),
        Country(iso: "LU", name: "Luxembourg",      flag: "🇱🇺", dialCode: "+352"),
        Country(iso: "MY", name: "Malaysia",        flag: "🇲🇾", dialCode: "+60"),
        Country(iso: "MT", name: "Malta",           flag: "🇲🇹", dialCode: "+356"),
        Country(iso: "MX", name: "Mexico",          flag: "🇲🇽", dialCode: "+52"),
        Country(iso: "MA", name: "Morocco",         flag: "🇲🇦", dialCode: "+212"),
        Country(iso: "NL", name: "Netherlands",     flag: "🇳🇱", dialCode: "+31"),
        Country(iso: "NZ", name: "New Zealand",     flag: "🇳🇿", dialCode: "+64"),
        Country(iso: "NI", name: "Nicaragua",       flag: "🇳🇮", dialCode: "+505"),
        Country(iso: "NG", name: "Nigeria",         flag: "🇳🇬", dialCode: "+234"),
        Country(iso: "NO", name: "Norway",          flag: "🇳🇴", dialCode: "+47"),
        Country(iso: "OM", name: "Oman",            flag: "🇴🇲", dialCode: "+968"),
        Country(iso: "PK", name: "Pakistan",        flag: "🇵🇰", dialCode: "+92"),
        Country(iso: "PA", name: "Panama",          flag: "🇵🇦", dialCode: "+507"),
        Country(iso: "PY", name: "Paraguay",        flag: "🇵🇾", dialCode: "+595"),
        Country(iso: "PE", name: "Peru",            flag: "🇵🇪", dialCode: "+51"),
        Country(iso: "PH", name: "Philippines",     flag: "🇵🇭", dialCode: "+63"),
        Country(iso: "PL", name: "Poland",          flag: "🇵🇱", dialCode: "+48"),
        Country(iso: "PT", name: "Portugal",        flag: "🇵🇹", dialCode: "+351"),
        Country(iso: "PR", name: "Puerto Rico",     flag: "🇵🇷", dialCode: "+1"),
        Country(iso: "QA", name: "Qatar",           flag: "🇶🇦", dialCode: "+974"),
        Country(iso: "RO", name: "Romania",         flag: "🇷🇴", dialCode: "+40"),
        Country(iso: "RU", name: "Russia",          flag: "🇷🇺", dialCode: "+7"),
        Country(iso: "SA", name: "Saudi Arabia",    flag: "🇸🇦", dialCode: "+966"),
        Country(iso: "RS", name: "Serbia",          flag: "🇷🇸", dialCode: "+381"),
        Country(iso: "SG", name: "Singapore",       flag: "🇸🇬", dialCode: "+65"),
        Country(iso: "SK", name: "Slovakia",        flag: "🇸🇰", dialCode: "+421"),
        Country(iso: "SI", name: "Slovenia",        flag: "🇸🇮", dialCode: "+386"),
        Country(iso: "ZA", name: "South Africa",    flag: "🇿🇦", dialCode: "+27"),
        Country(iso: "KR", name: "South Korea",     flag: "🇰🇷", dialCode: "+82"),
        Country(iso: "ES", name: "Spain",           flag: "🇪🇸", dialCode: "+34"),
        Country(iso: "SE", name: "Sweden",          flag: "🇸🇪", dialCode: "+46"),
        Country(iso: "CH", name: "Switzerland",     flag: "🇨🇭", dialCode: "+41"),
        Country(iso: "TW", name: "Taiwan",          flag: "🇹🇼", dialCode: "+886"),
        Country(iso: "TH", name: "Thailand",        flag: "🇹🇭", dialCode: "+66"),
        Country(iso: "TR", name: "Turkey",          flag: "🇹🇷", dialCode: "+90"),
        Country(iso: "UA", name: "Ukraine",         flag: "🇺🇦", dialCode: "+380"),
        Country(iso: "AE", name: "United Arab Emirates", flag: "🇦🇪", dialCode: "+971"),
        Country(iso: "GB", name: "United Kingdom",  flag: "🇬🇧", dialCode: "+44"),
        Country(iso: "US", name: "United States",   flag: "🇺🇸", dialCode: "+1"),
        Country(iso: "UY", name: "Uruguay",         flag: "🇺🇾", dialCode: "+598"),
        Country(iso: "VE", name: "Venezuela",       flag: "🇻🇪", dialCode: "+58"),
        Country(iso: "VN", name: "Vietnam",         flag: "🇻🇳", dialCode: "+84")
    ]
}
