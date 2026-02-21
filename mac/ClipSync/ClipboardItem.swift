


import Foundation


// Purpose: Enum that models clipboard direction behavior in this module.
// Responsibilities: Encapsulates clipboard direction behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
enum ClipboardDirection {
    case sent
    case received
}


// Purpose: Struct that models clipboard item behavior in this module.
// Responsibilities: Encapsulates clipboard item behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct ClipboardItem: Identifiable, Equatable {
    let id = UUID()
    let content: String
    let timestamp: Date
    let deviceName: String
    let direction: ClipboardDirection


    var timeAgo: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
