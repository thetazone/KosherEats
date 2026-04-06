import Foundation

/// Order-scoped chat message. Matches the backend ChatMessage struct.
/// The `senderRole` drives bubble alignment + name label in the UI.
struct ChatMessage: Codable, Identifiable {
    let id: String
    let orderID: String
    let senderUserID: String
    let senderRole: String // "consumer" | "seller" | "courier"
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
        case "courier": return "Driver"
        case "seller": return "Restaurant"
        case "consumer": return "You"
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
