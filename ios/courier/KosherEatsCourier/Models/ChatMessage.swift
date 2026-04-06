import Foundation

/// Order-scoped chat message — same backend struct as consumer + seller.
struct ChatMessage: Codable, Identifiable {
    let id: String
    let orderID: String
    let senderUserID: String
    let senderRole: String
    let text: String
    let createdAt: Date

    enum CodingKeys: String, CodingKey {
        case id, text
        case orderID = "order_id"
        case senderUserID = "sender_user_id"
        case senderRole = "sender_role"
        case createdAt = "created_at"
    }

    var senderLabel: String {
        switch senderRole {
        case "courier": return "You"
        case "seller": return "Restaurant"
        case "consumer": return "Customer"
        default: return senderRole.capitalized
        }
    }

    var shortTime: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .none
        formatter.timeStyle = .short
        return formatter.string(from: createdAt)
    }
}
