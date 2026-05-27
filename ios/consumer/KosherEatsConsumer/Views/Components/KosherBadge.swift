import SwiftUI

struct KosherBadge: View {
    let certification: KosherCertification
    let size: BadgeSize

    enum BadgeSize {
        case compact
        case small
        case regular

        var iconSize: CGFloat {
            switch self {
            case .compact: return 10
            case .small: return 12
            case .regular: return 16
            }
        }

        var fontSize: CGFloat {
            switch self {
            case .compact: return 10
            case .small: return 11
            case .regular: return 14
            }
        }

        var paddingH: CGFloat {
            switch self {
            case .compact: return 6
            case .small: return 8
            case .regular: return 12
            }
        }

        var paddingV: CGFloat {
            switch self {
            case .compact: return 3
            case .small: return 4
            case .regular: return 6
            }
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: certification.symbolName)
                .font(.system(size: size.iconSize, weight: .bold))
            Text(certification.displayName)
                .font(.system(size: size.fontSize, weight: .bold))
        }
        .foregroundColor(.kePrimary)
        .padding(.horizontal, size.paddingH)
        .padding(.vertical, size.paddingV)
        .background(Color.kePrimary.opacity(0.15))
        .cornerRadius(size == .compact ? 4 : 8)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(certification.displayName) kosher certification")
    }
}
