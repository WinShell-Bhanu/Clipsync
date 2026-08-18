import SwiftUI

// Single row in the clipboard history list. Content is masked until hovered.
// Image items show a compact label instead of a preview (RAM-friendly).
struct ClipboardHistoryRow: View {
    let item: ClipboardItem
    let isHovered: Bool

    #if DEBUG
    #endif

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text("Copied \(timeAgo(from: item.timestamp))")
                        .font(.system(size: 10, weight: .medium))
                        .foregroundColor(.black.opacity(0.5))
                        .textCase(.uppercase)
                    Spacer()
                }

                if item.isImage {
                    // No preview — lightweight text label to save RAM.
                    HStack(spacing: 6) {
                        Text("📸")
                            .font(.system(size: 14))
                        Text("Image copiée")
                            .font(.system(size: 13, weight: .medium))
                            .foregroundColor(.black.opacity(0.7))
                    }
                } else if isHovered {
                    Text(item.content.prefix(100) + (item.content.count > 100 ? "..." : ""))
                        .font(.system(size: 13))
                        .foregroundColor(.black.opacity(0.9))
                        .lineLimit(2)
                        .transition(.opacity)
                } else {
                    Text("••••••••••••••••••••••••••••")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundColor(.black.opacity(0.3))
                        .tracking(2)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }


    private func timeAgo(from date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
