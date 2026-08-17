import SwiftUI

// MARK: - History Tab (Figma redesign)

enum HistoryFilter: String, CaseIterable {
    case all = "All", text = "Text", otp = "OTP", links = "Links", screenshots = "Screenshots", files = "Files"

    // SF Symbol name for the icon box
    var systemIcon: String {
        switch self {
        case .all:         return "square.grid.2x2"
        case .text:        return "text.alignleft"
        case .otp:         return "key.horizontal"
        case .links:       return "link"
        case .screenshots: return "camera.viewfinder"
        case .files:       return "doc.fill"
        }
    }
}

struct NHSHistoryItem: Identifiable {
    let id: UUID
    let content: String
    let direction: String
    let timeAgo: String
    let type: HistoryFilter
    let isSuccess: Bool
    let filePath: String?
}

struct NHSHistoryTab: View {
    @StateObject private var clipboardManager = ClipboardManager.shared
    @State private var selectedFilter: HistoryFilter = .all
    @State private var hoveredId: UUID?         = nil
    @State private var searchText: String       = ""

    // Optional static data injected by previews to avoid ClipboardManager side-effects
    var previewItems: [NHSHistoryItem]? = nil

    // Map real clipboard history → display items
    private var allItems: [NHSHistoryItem] {
        // Use injected preview data if available
        if let previewItems { return previewItems }

        let mapped: [NHSHistoryItem] = clipboardManager.history.prefix(40).map { item in
            let type: HistoryFilter =
                item.isImage ? .screenshots :
                item.isFile ? .files :
                (item.content.count <= 8 && item.content.allSatisfy(\.isNumber)) ? .otp :
                (item.content.hasPrefix("http") || item.content.hasPrefix("www")) ? .links : .text
            let dir = item.direction == .received ? "Received from Android" : "Sent to Android"
            return NHSHistoryItem(
                id: item.id,
                content: item.isFile ? item.content : (item.isImage ? "Image captured" : item.content),
                direction: dir,
                timeAgo: item.timeAgo,
                type: type,
                isSuccess: true,
                filePath: item.filePath
            )
        }
        return mapped
    }

    private var filteredItems: [NHSHistoryItem] {
        var items = selectedFilter == .all ? allItems : allItems.filter { $0.type == selectedFilter }
        if !searchText.isEmpty {
            items = items.filter { $0.content.localizedCaseInsensitiveContains(searchText) }
        }
        return items
    }

    // Static placeholder data — shared with Preview
    static let placeholderItems: [NHSHistoryItem] = [
        NHSHistoryItem(id: UUID(), content: "npm install, its just a sample text", direction: "Sent to Android",       timeAgo: "15 min ago", type: .text,        isSuccess: true, filePath: nil),
        NHSHistoryItem(id: UUID(), content: "291 483",                              direction: "Received from Android", timeAgo: "2m ago",    type: .otp,         isSuccess: true, filePath: nil),
        NHSHistoryItem(id: UUID(), content: "github.com/bunty/clipsync",            direction: "Sent to Android",       timeAgo: "1h ago",   type: .links,       isSuccess: false, filePath: nil),
        NHSHistoryItem(id: UUID(), content: "Screenshot.png",                       direction: "Received from Android", timeAgo: "3h ago",   type: .screenshots, isSuccess: true, filePath: nil),
        NHSHistoryItem(id: UUID(), content: "sudo xcode-select --install",          direction: "Sent to Android",       timeAgo: "5h ago",   type: .text,        isSuccess: true, filePath: nil),
        NHSHistoryItem(id: UUID(), content: "https://figma.com/design/xyz",         direction: "Received from Android", timeAgo: "Yesterday",type: .links,       isSuccess: true, filePath: nil),
    ]

    var body: some View {
        // ── Absolute positioning for reliable scroll on macOS ──
        // Figma coordinates offset by +60px to clear the title bar controls (close/minimize/zoom).
        // ZStack lets us give the ScrollView an EXACT frame height so macOS fires scroll events.
        //
        //  y-anchor  element          h
        //  110       search bar       50
        //  170       filter chips     35
        //  210       cards scroll     365  (extended behind floating nav)

        ZStack(alignment: .topLeading) {

            // ── Search bar ── y=110, x=30, w=530, h=50
            HStack(spacing: 12) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18, weight: .regular))  // Figma: 24dp icon
                    .foregroundColor(.black.opacity(0.8))
                    .frame(width: 24, height: 24)

                TextField("", text: $searchText)
                    .textFieldStyle(.plain)
                    .font(Font.custom("SF Pro Display", size: 15)
                          .weight(.regular))
                    .foregroundColor(.black)
                    .overlay(
                        // Custom placeholder when empty
                        Group {
                            if searchText.isEmpty {
                                Text("Search history...")
                                    .font(Font.custom("SF Pro Display", size: 15))
                                    .foregroundColor(.black.opacity(0.38))
                                    .allowsHitTesting(false)
                            }
                        },
                        alignment: .leading
                    )

                if !searchText.isEmpty {
                    Button { searchText = "" } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 14))
                            .foregroundColor(.black.opacity(0.3))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .frame(width: 530, height: 50)
            .background(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.white.opacity(0.6))
                    .shadow(color: .black.opacity(0.2), radius: 30, x: 0, y: 0)
            )
            .offset(x: 30, y: 110)

            // ── Filter chips ── y=170, x=30, w=530, h=35
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(HistoryFilter.allCases, id: \.self) { filter in
                        let isSelected = selectedFilter == filter
                        Text(filter.rawValue)
                            .font(.system(size: 13, weight: isSelected ? .semibold : .medium))
                            .foregroundColor(.black)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 5)
                            .background(
                                Capsule()
                                    // Selected: slightly opaque white; unselected: lighter white
                                    .fill(Color.white.opacity(isSelected ? 0.85 : 0.55))
                                    .shadow(color: .black.opacity(0.1), radius: 8, x: 0, y: 2)
                            )
                            // No stroke overlay — removes edge distortion artifact
                            .scaleEffect(isSelected ? 1.04 : 1.0)
                            .animation(.spring(response: 0.28, dampingFraction: 0.65), value: isSelected)
                            .onTapGesture {
                                withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
                                    selectedFilter = filter
                                }
                            }
                    }
                    .padding(.trailing, 4) // small gap after last chip
                }
            }
            .frame(width: 530, height: 35)
            .offset(x: 30, y: 170)

            // ── Cards scrollview ── y=210, x=30, w=530, explicit h=285
            // Explicit height is critical: tells macOS exactly where the scroll viewport is.
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 5) {
                    if filteredItems.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "clock.badge.xmark")
                                .font(.system(size: 36, weight: .light))
                                .foregroundColor(.white.opacity(0.5))
                            Text("No history yet")
                                .font(.system(size: 15, weight: .medium))
                                .foregroundColor(.white.opacity(0.5))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                    } else {
                        ForEach(Array(filteredItems.enumerated()), id: \.element.id) { index, item in
                            let count = filteredItems.count
                            let topR:    CGFloat = index == 0         ? 24 : 12
                            let bottomR: CGFloat = index == count - 1 ? 24 : 12

                            NHSHistoryItemCard(
                                item: item,
                                isHovered: hoveredId == item.id,
                                topCornerRadius: topR,
                                bottomCornerRadius: bottomR
                            )
                            .onHover { hovering in
                                withAnimation(.easeInOut(duration: 0.15)) {
                                    hoveredId = hovering ? item.id : nil
                                }
                            }
                        }

                        // Scroll indicator pill (Figma 295:79)
                        RoundedRectangle(cornerRadius: 32)
                            .fill(Color.white.opacity(0.6))
                            .frame(width: 70, height: 10)
                            .shadow(color: .black.opacity(0.15), radius: 20)
                            .padding(.top, 8)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.bottom, 95)
            }
            .frame(width: 530, height: 365)   // explicit viewport — scroll fires reliably on macOS
            .mask(
                LinearGradient(
                    gradient: Gradient(stops: [
                        .init(color: .black, location: 0.0),
                        .init(color: .black, location: 0.75),
                        .init(color: .clear, location: 0.95)
                    ]),
                    startPoint: .top,
                    endPoint: .bottom
                )
            )
            .offset(x: 30, y: 210)
        }
        .frame(width: 590, height: 590, alignment: .topLeading)       // fixed 590×590 — no responsive drift
    }
}


// MARK: - History Item Card (Figma stacked style)

struct NHSHistoryItemCard: View {
    let item: NHSHistoryItem
    let isHovered: Bool
    let topCornerRadius: CGFloat
    let bottomCornerRadius: CGFloat

    // Icon box color from Figma node 316:143: rgba(183,183,224,0.4)
    private let iconBoxColor = Color(red: 0.718, green: 0.718, blue: 0.878).opacity(0.4)

    var body: some View {
        HStack(spacing: 14) {

            // Icon box (Figma: 40×40, cornerRadius=12, rgba(183,183,224,0.4))
            ZStack {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(iconBoxColor)
                    .frame(width: 40, height: 40)
                Image(systemName: item.type.systemIcon)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(Color(red: 0.275, green: 0.282, blue: 0.831))
            }

            // Text column
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    // Type badge
                    Text(item.type.rawValue)
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(Color(red: 0.275, green: 0.282, blue: 0.831))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(
                            RoundedRectangle(cornerRadius: 8)
                                .fill(iconBoxColor)
                        )
                    Spacer()
                    // Timestamp
                    Text(item.timeAgo)
                        .font(.system(size: 12))
                        .foregroundColor(Color(hex: "585555"))
                }

                // Content — masked until hover (OTP shows asterisks unless hovered)
                Text(item.type == .otp ? (isHovered ? item.content : String(repeating: "*", count: item.content.isEmpty ? 6 : item.content.count)) : item.content)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.black)
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .animation(.easeInOut(duration: 0.18), value: isHovered)

                if !item.isSuccess {
                    HStack(spacing: 12) {
                        HStack(spacing: 3) {
                            Image(systemName: "exclamationmark.circle.fill")
                                .font(.system(size: 10))
                            Text("Failed")
                                .font(.system(size: 11))
                        }
                        .foregroundColor(Color(hex: "FF3B30"))

                        HStack(spacing: 3) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 10))
                            Text("Retry")
                                .font(.system(size: 11))
                        }
                        .foregroundColor(Color(red: 0.275, green: 0.282, blue: 0.831))
                        .onTapGesture { }
                    }
                    .padding(.top, 2)
                }
            }

            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 70)
        .background(
            Color.white.opacity(isHovered ? 0.55 : 0.60)
                .shadow(.inner(color: .white.opacity(0.3), radius: 1, x: 0, y: 1))
        )
        .onTapGesture {
            if item.type == .files || item.type == .screenshots {
                if let path = item.filePath {
                    let fileUrl = URL(fileURLWithPath: path)
                    var isDir: ObjCBool = false
                    if FileManager.default.fileExists(atPath: fileUrl.path, isDirectory: &isDir) {
                        NSWorkspace.shared.activateFileViewerSelecting([fileUrl])
                        return
                    }
                } else if item.direction == "Received from Android" {
                    // Fallback to searching Downloads folder
                    if let downloadsUrl = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first {
                        let fileUrl = downloadsUrl.appendingPathComponent(item.content)
                        var isDir: ObjCBool = false
                        if FileManager.default.fileExists(atPath: fileUrl.path, isDirectory: &isDir) {
                            NSWorkspace.shared.activateFileViewerSelecting([fileUrl])
                            return
                        }
                    }
                }
            }
            // For text/links/OTP, copy to clipboard
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(item.content, forType: .string)
        }
        .clipShape(
            .rect(
                topLeadingRadius:     topCornerRadius,
                bottomLeadingRadius:  bottomCornerRadius,
                bottomTrailingRadius: bottomCornerRadius,
                topTrailingRadius:    topCornerRadius
            )
        )
        .scaleEffect(isHovered ? 1.008 : 1.0)
        .animation(.spring(response: 0.22, dampingFraction: 0.7), value: isHovered)
    }
}



#Preview("History Tab") {
    ZStack {
        MeshBackground(introProgress: 1.0, shouldAnimate: false)
        // Inject static data so ClipboardManager is never touched in Canvas
        NHSHistoryTab(previewItems: NHSHistoryTab.placeholderItems)
    }
    .frame(width: 590, height: 590)
}

