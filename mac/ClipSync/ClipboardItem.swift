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
    /// `true` when this history entry represents an image transfer (no preview stored).
    let isImage: Bool
    let isFile: Bool
    let filePath: String?

    init(content: String, timestamp: Date, deviceName: String, direction: ClipboardDirection, isImage: Bool = false, isFile: Bool = false, filePath: String? = nil) {
        self.content = content
        self.timestamp = timestamp
        self.deviceName = deviceName
        self.direction = direction
        self.isImage = isImage
        self.isFile = isFile
        self.filePath = filePath
    }

    /// Relative time string for display in the clipboard history list.
    var timeAgo: String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: timestamp, relativeTo: Date())
    }
}
