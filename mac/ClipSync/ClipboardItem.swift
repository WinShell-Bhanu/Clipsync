// ClipboardItem.swift
// Value type representing a single clipboard sync event (sent or received).
// Used to populate the history list in HomeScreen.

import Foundation

// MARK: - ClipboardDirection

/// Indicates whether a clipboard item was sent from Mac to Android, or received from Android.
enum ClipboardDirection {
    case sent
    case received
}

// MARK: - ClipboardItem

/// Immutable record of one clipboard sync event; conforming to Identifiable for SwiftUI lists.
struct ClipboardItem: Identifiable, Equatable {
    let id = UUID()
    let content: String
    let timestamp: Date
    let deviceName: String
    let direction: ClipboardDirection

    /// Relative time string for display in the clipboard history list.
    var timeAgo: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
