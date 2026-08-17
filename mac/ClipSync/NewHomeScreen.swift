// NewHomeScreen.swift
// Redesigned home screen matching the Figma layout pixel-for-pixel.
// Card container: uses InnerGlassCard from HomeScreen.swift (unchanged).
// Quick Action cards use the exact CheckEncryptionCard bounce animation.
// Clipboard History is the exact card from HomeScreen.swift.
// Bottom nav pill mirrors the Android LiquidGlassNavBar animation.

import SwiftUI
import AppKit
import UniformTypeIdentifiers

// MARK: - Navigation Tab

enum MacNavigationTab: CaseIterable {
    case home, history

    var title: String {
        switch self {
        case .home:    return "Home"
        case .history: return "History"
        }
    }
    var iconName: String {
        switch self {
        case .home:    return "square.grid.2x2.fill"
        case .history: return "clock.fill"
        }
    }
}

// MARK: - NewHomeScreen

struct NewHomeScreen: View {
    @ObservedObject private var clipboardManager = ClipboardManager.shared
    @ObservedObject private var pairingManager   = PairingManager.shared
    @ObservedObject private var server        = ClipSyncServer.shared
    @ObservedObject private var updateManager    = MacUpdateManager.shared

    @State private var currentTab: MacNavigationTab = .home
    @State private var contentOpacity: Double = 0
    @State private var contentOffset: CGFloat = 20
    @State private var navigateToRePair  = false
    @State private var showResetConfirm  = false
    @State private var showDiagnosticConsole = false
    @State private var isFileDragTargeted = false

    var body: some View {
        ZStack(alignment: .top) {

            // ── Exact same mesh background as HomeScreen.swift ──
            MeshBackground(introProgress: 1.0, shouldAnimate: false)
                .ignoresSafeArea()

            // ── Tab Content: Home vs History ──
            if currentTab == .home {
                VStack(alignment: .leading, spacing: 20) {

                    // ── Transfer Progress Card (w=530, h=130) ──
                    NHSTransferCard()

                    // ── Two-column bento grid ──
                    HStack(alignment: .top, spacing: 15) {

                        // LEFT COLUMN: w=170
                        VStack(spacing: 20) {
                            NHSDeviceCard(deviceName: pairingManager.pairedDeviceName)
                            NHSSyncCard()
                        }

                        // RIGHT COLUMN: w=345
                        VStack(alignment: .leading, spacing: 20) {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 7.5) {
                                    NHSRePairButton {
                                        pairingManager.clearPairing {
                                            navigateToRePair = true
                                        } onFailure: { _ in
                                            navigateToRePair = true
                                        }
                                    }
                                    .frame(width: 110, height: 100)
                                    NHSQuickActionCard(iconName: "paperplane.fill",
                                                      title: "Send File") {
                                        let panel = NSOpenPanel()
                                        panel.allowsMultipleSelection = true
                                        panel.canChooseDirectories = false
                                        panel.canChooseFiles = true
                                        panel.title = "Select files to send to Android"
                                        if panel.runModal() == .OK {
                                            ClipSyncServer.shared.sendFiles(urls: panel.urls)
                                        }
                                    }
                                    NHSQuickActionCard(iconName: "stethoscope.circle.fill",
                                                      title: "Repair") {
                                        showDiagnosticConsole = true
                                    }
                                }
                                // Give buttons room to breathe when scaling so they don't clip against ScrollView bounds
                                .padding(.vertical, 10)
                                .padding(.horizontal, 15)
                            }
                            // Expand the clipping bounds by 30px to fit the 15px internal padding
                            .frame(width: 345 + 30)
                            // Shift the whole view left by 15px and up by 15px to perfectly align with the original layout grid
                            .offset(x: -15, y: -15)
                            .padding(.bottom, -30) // Remove the extra vertical height from layout footprint
                            NHSClipboardHistoryCard(showResetConfirm: $showResetConfirm)
                        }
                    }
                }
                .padding(.top, 115)
                .padding(.leading, 45)
                .padding(.trailing, 15)
                .opacity(contentOpacity)
                .offset(y: contentOffset)
                .transition(.asymmetric(
                    insertion: .opacity.combined(with: .offset(x: -20)),
                    removal:   .opacity.combined(with: .offset(x: -20))
                ))
            } else {
                NHSHistoryTab()
                    .transition(.asymmetric(
                        insertion: .opacity.combined(with: .offset(x: 20)),
                        removal:   .opacity.combined(with: .offset(x: 20))
                    ))
            }

            // ── Floating Pill Nav (always on top) ──
            VStack {
                Spacer()
                NHSNavPill(tabs: MacNavigationTab.allCases,
                           selectedTab: $currentTab)
                    .padding(.bottom, 42)
            }

            VStack {
                Spacer()
                HStack {
                    Spacer()
                    NHSFileDropTarget(isTargeted: isFileDragTargeted, isSending: server.isSendingFile)
                        .padding(.trailing, 28)
                        .padding(.bottom, 28)
                }
            }
        }
        .frame(width: 590, height: 590)
        .ignoresSafeArea()
        .onDrop(of: [.fileURL], isTargeted: $isFileDragTargeted) { providers in
            handleDroppedFiles(providers)
        }
        .navigationDestination(isPresented: Binding(
            get: { navigateToRePair },
            set: { if !$0 { navigateToRePair = false } }
        )) {
            QRGenScreen()
        }
        .onAppear {
            if !clipboardManager.isSyncPaused {
                clipboardManager.startMonitoring()
                clipboardManager.listenForAndroidClipboard()
            }
            withAnimation(.spring(response: 0.4, dampingFraction: 1.0).delay(0.1)) {
                contentOpacity = 1
                contentOffset  = 0
            }
        }
        .alert("Reset Pairing?", isPresented: $showResetConfirm) {
            Button("Reset", role: .destructive) { pairingManager.clearPairing() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will disconnect your Android device and delete all pairing data. You'll need to scan the QR code again to reconnect.")
        }
        .sheet(isPresented: $showDiagnosticConsole) {
            MacDiagnosticConsole()
        }
    }

    private func handleDroppedFiles(_ providers: [NSItemProvider]) -> Bool {
        var urls: [URL] = []
        let lock = NSLock()
        let group = DispatchGroup()

        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier) {
            group.enter()
            provider.loadItem(forTypeIdentifier: UTType.fileURL.identifier, options: nil) { item, _ in
                defer { group.leave() }
                var loadedURL: URL?

                if let data = item as? Data,
                   let url = URL(dataRepresentation: data, relativeTo: nil) {
                    loadedURL = url
                } else if let url = item as? URL {
                    loadedURL = url
                } else if let string = item as? String,
                          let url = URL(string: string) {
                    loadedURL = url
                }

                if let loadedURL {
                    lock.lock()
                    urls.append(loadedURL)
                    lock.unlock()
                }
            }
        }

        group.notify(queue: .main) {
            ClipSyncServer.shared.sendFiles(urls: urls)
        }
        return true
    }
}

// MARK: - Transfer Progress Card (w=530, h=130)

private struct NHSTransferCard: View {
    @ObservedObject var server = ClipSyncServer.shared
    private let totalSegments = 20

    var body: some View {
        let isReceiving = server.transferProgress > 0 && server.transferProgress < 1.0
        let isSending = server.isSendingFile
        let didCompleteReceive = server.transferProgress >= 1.0 && server.bytesReceived > 0
        let didCompleteSend = server.sendFileProgress >= 1.0 && !server.isSendingFile
        
        let progress = isSending ? server.sendFileProgress : (isReceiving ? server.transferProgress : ((didCompleteReceive || didCompleteSend) ? 1.0 : 0.0))
        let filledSegments = Int(progress * Double(totalSegments))
        let percentage = Int(progress * 100)
        let isSyncing = isSending || isReceiving
        let statusText = isSyncing ? (server.currentTransferFileName ?? "Syncing...") : "Ready"
        let speedText = server.transferSpeedString.isEmpty ? "0 MB/s" : server.transferSpeedString
        let stateText = isSending ? "Sending" : (isReceiving ? "Receiving" : ((didCompleteReceive || didCompleteSend) ? "Complete" : "Idle"))
        let iconName = isSending ? "arrow.up.doc.fill" : (isReceiving ? "arrow.down.doc.fill" : "doc.fill")

        InnerGlassCard {
            VStack(spacing: 0) {

                // Header row: icon + filename + progress badge
                HStack(spacing: 10) {
                    Image(systemName: iconName)
                        .font(.system(size: 20))
                        .foregroundColor(Color(red: 0.04, green: 0.52, blue: 1.0))

                    Text(statusText)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(Color(hex: "020202"))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .frame(maxWidth: 150, alignment: .leading)

                    Spacer()

                    // Progress badge: 75%
                    HStack(spacing: 0) {
                        Text("\(percentage)%")
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(.black)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 5)
                    .background(Color(red: 0.565, green: 0.639, blue: 0.937).opacity(0.25))
                    .cornerRadius(10)
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)

                // Segmented progress bar (h=10, segments=20, gap=5)
                HStack(spacing: 5) {
                    ForEach(0..<totalSegments, id: \.self) { i in
                        RoundedRectangle(cornerRadius: 12)
                            .fill(segmentColor(at: i, filledCount: filledSegments))
                            .frame(height: 10)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)

                // Divider
                Rectangle()
                    .fill(Color.black.opacity(0.09))
                    .frame(height: 1)
                    .padding(.horizontal, 10)
                    .padding(.top, 10)

                // Footer stats row
                HStack {
                    Text("Speed: \(speedText)")
                    Spacer()
                    Text("Status: \(stateText)")
                    Spacer()
                    Text("Health: 100%")
                }
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(Color(hex: "463E3E").opacity(0.85))
                .padding(.horizontal, 13)
                .padding(.top, 8)
                .padding(.bottom, 14)
            }
        }
        .frame(width: 530, height: 130)
    }

    private func segmentColor(at i: Int, filledCount: Int) -> Color {
        let blue = Color(red: 0.04, green: 0.52, blue: 1.0)
        if i < filledCount { return blue }
        return blue.opacity(0.22)
    }
}

// MARK: - File Drop Target

private struct NHSFileDropTarget: View {
    let isTargeted: Bool
    let isSending: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: isSending ? "arrow.up.circle.fill" : "tray.and.arrow.down.fill")
                .font(.system(size: 18, weight: .semibold))
                .foregroundColor(Color(hex: "10449F"))

            VStack(alignment: .leading, spacing: 2) {
                Text(isSending ? "Sending to Android" : "Drop to Android")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(Color(hex: "020202"))
                Text(isSending ? "Transfer in progress" : "Release files here")
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(Color(hex: "463E3E").opacity(0.75))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            EffectView(material: .hudWindow, blendingMode: .behindWindow)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(Color.white.opacity(0.45), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.18), radius: 18, x: 0, y: 10)
        .scaleEffect(isTargeted ? 1.0 : 0.96)
        .opacity(isTargeted || isSending ? 1.0 : 0.0)
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: isTargeted)
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: isSending)
        .allowsHitTesting(false)
    }
}

// MARK: - Device Info Card (w=170, h=150)

private struct NHSDeviceCard: View {
    let deviceName: String
    @ObservedObject var wakeupReceiver = WakeupReceiver.shared

    var body: some View {
        let battery = wakeupReceiver.lastPing?.battery

        InnerGlassCard {
            VStack(spacing: 0) {
                Spacer()
                // Phone icon (34×34 as in Figma)
                Image(systemName: "iphone")
                    .font(.system(size: 28, weight: .light))
                    .foregroundColor(Color(hex: "10449F"))

                Spacer().frame(height: 10)

                // Device name
                Text(deviceName.isEmpty ? "No Device" : deviceName)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundColor(Color(hex: "10449F"))
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .padding(.horizontal, 10)

                Spacer().frame(height: 14)

                // Battery chip
                HStack(spacing: 7) {
                    if let b = battery {
                        NHSChip(icon: "battery.75", label: "\(b)%")
                    } else {
                        NHSChip(icon: "battery.0", label: "--%")
                    }
                }

                Spacer()
            }
            .frame(maxWidth: .infinity)
        }
        .frame(width: 170, height: 150)
    }
}

private struct NHSChip: View {
    let icon: String
    let label: String

    var body: some View {
        HStack(spacing: 3) {
            Image(systemName: icon)
                .font(.system(size: 9))
            Text(label)
                .font(.system(size: 11, weight: .medium))
        }
        .foregroundColor(.black)
        .padding(.horizontal, 7)
        .padding(.vertical, 4)
        .background(Color.white.opacity(0.65))
        .cornerRadius(12)
    }
}

// MARK: - Sync Actions Card (w=170, h=180)
// Figma shows: icon box (30×30) + text label, NO toggle, with separators.
// Each row is 60px tall (3×60 = 180px total).

private struct NHSSyncCard: View {
    @AppStorage("syncToMac")   private var syncToMac   = true
    @AppStorage("syncFromMac") private var syncFromMac = true
    @AppStorage("syncOTPs")    private var syncOTPs    = true

    var body: some View {
        InnerGlassCard {
            VStack(spacing: 0) {
                NHSSyncRow(icon: "square.and.arrow.down",
                           label: "Sync from\nAndroid",
                           isOn: syncFromMac)
                    .frame(height: 60)
                    .onTapGesture { syncFromMac.toggle() }

                Rectangle()
                    .fill(Color.black.opacity(0.1))
                    .frame(height: 0.5)
                    .padding(.horizontal, 10)

                NHSSyncRow(icon: "square.and.arrow.up",
                           label: "Sync to\nAndroid",
                           isOn: syncToMac)
                    .frame(height: 60)
                    .onTapGesture { syncToMac.toggle() }

                Rectangle()
                    .fill(Color.black.opacity(0.1))
                    .frame(height: 0.5)
                    .padding(.horizontal, 10)

                NHSSyncRow(icon: "checkmark.message",
                           label: "Sync OTPs",
                           isOn: syncOTPs)
                    .frame(height: 60)
                    .onTapGesture { syncOTPs.toggle() }
            }
            .frame(maxWidth: .infinity)
        }
        .frame(width: 170, height: 180)
    }
}

private struct NHSSyncRow: View {
    let icon: String
    let label: String
    let isOn: Bool

    var body: some View {
        HStack(spacing: 0) {
            // 26×26 icon box
            ZStack {
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color(red: 0.659, green: 0.686, blue: 0.871).opacity(0.6))
                    .frame(width: 26, height: 26)
                Image(systemName: icon)
                    .font(.system(size: 11))
                    .foregroundColor(.black)
            }

            Spacer(minLength: 0)
            
            Text(label)
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(.black)
                .multilineTextAlignment(.center)
                .lineSpacing(1)

            Spacer(minLength: 0)

            // ── Premium Liquid Glass Toggle ──
            NHSLiquidGlassToggle(isOn: isOn)
        }
        .padding(.horizontal, 10)
        .opacity(isOn ? 1.0 : 0.45)
        .contentShape(Rectangle())
    }
}

// Liquid Glass Toggle — premium frosted knob, specular highlight, spring snap
private struct NHSLiquidGlassToggle: View {
    let isOn: Bool

    var body: some View {
        ZStack(alignment: isOn ? .trailing : .leading) {
            // Track — frosted glass look
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay(
                    Capsule()
                        .fill(isOn
                              ? Color(red: 0.275, green: 0.282, blue: 0.831).opacity(0.35)
                              : Color.black.opacity(0.06))
                )
                .overlay(
                    Capsule()
                        .stroke(Color.white.opacity(isOn ? 0.5 : 0.3), lineWidth: 0.75)
                )
                .frame(width: 34, height: 19)

            // Knob — white with specular highlight + shadow
            ZStack {
                Circle()
                    .fill(Color.white)
                    .frame(width: 15, height: 15)
                    .shadow(color: Color.black.opacity(0.18), radius: 2, x: 0, y: 1)

                // Specular top-left glint
                Circle()
                    .fill(
                        RadialGradient(
                            gradient: Gradient(colors: [
                                Color.white.opacity(0.6),
                                Color.clear
                            ]),
                            center: UnitPoint(x: 0.3, y: 0.25),
                            startRadius: 0,
                            endRadius: 7
                        )
                    )
                    .frame(width: 15, height: 15)
            }
            .padding(2)
        }
        .frame(width: 34, height: 19)
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: isOn)
    }
}

// MARK: - Quick Action Card (w=110, h=100)
// Bounce animation is the EXACT same as CheckEncryptionCard in HomeScreen.swift.

private struct NHSQuickActionCard: View {
    let iconName: String
    let title: String
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            InnerGlassCard {
                VStack(spacing: 10) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                            .fill(Color(red: 0.659, green: 0.686, blue: 0.871).opacity(0.6))
                            .frame(width: 40, height: 40)
                        Image(systemName: iconName)
                            .font(.system(size: 18))
                            .foregroundColor(.black)
                    }
                    Text(title)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(.black)
                        .lineLimit(1)
                }
            }
            .frame(width: 110, height: 100)
        }
        .buttonStyle(.plain)
        // Exact bounce animation from CheckEncryptionCard:
        .scaleEffect(isHovered ? 1.03 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: isHovered)
        .onHover { hovering in
            withAnimation(.spring(response: 0.3, dampingFraction: 1.0)) { isHovered = hovering }
        }
    }
}

// MARK: - RePair Button (Exact dot-to-dot from HomeScreen.swift)

private struct NHSRePairButton: View {
    let action: () -> Void
    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.black.opacity(0.1))
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )

                HStack(spacing: 6) {
                    if isHovered {
                        if #available(macOS 14.0, *) {
                            Image(systemName: "qrcode")
                                .font(.system(size: 24))
                                .symbolEffect(.variableColor.iterative, options: .nonRepeating)
                                .transition(.scale.combined(with: .opacity))
                        } else {
                            Image(systemName: "qrcode")
                                .font(.system(size: 24))
                        }
                    }
                    Text("RePair")
                        .font(.system(size: 18, weight: .bold))
                }
                .foregroundColor(.black)
            }
        }
        .buttonStyle(.plain)
        .scaleEffect(isHovered ? 1.15 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 0.5), value: isHovered)
        .onHover { hovering in
            isHovered = hovering
        }
    }
}



// MARK: - Clipboard History Embed (w=345, h=230)
// Exact card content from HomeScreen.swift — no changes.

private struct NHSClipboardHistoryCard: View {
    @StateObject private var clipboardManager = ClipboardManager.shared
    @Binding var showResetConfirm: Bool
    @State private var hoveredClipboardItem: UUID? = nil

    var body: some View {
        InnerGlassCard {
            VStack(alignment: .leading, spacing: 0) {
                Text("Clipboard History")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(.black.opacity(0.5))
                    .padding(.top, 14)
                    .padding(.leading, 16)
                    .padding(.bottom, 8)

                Divider()
                    .background(Color.black.opacity(0.1))
                    .padding(.horizontal, 16)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        if clipboardManager.history.isEmpty {
                            Text("No recent syncs")
                                .font(.system(size: 13))
                                .foregroundColor(.black.opacity(0.5))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 20)
                        } else {
                            ForEach(Array(clipboardManager.history.prefix(20)), id: \.id) { item in
                                ClipboardHistoryRow(
                                    item: item,
                                    isHovered: hoveredClipboardItem == item.id
                                )
                                .onHover { hovering in
                                    withAnimation(.spring(response: 0.3, dampingFraction: 1.0)) {
                                        hoveredClipboardItem = hovering ? item.id : nil
                                    }
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)

                                if item.id != clipboardManager.history.prefix(20).last?.id {
                                    Divider()
                                        .background(Color.white.opacity(0.2))
                                        .padding(.horizontal, 16)
                                }
                            }
                        }
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    HStack(spacing: 8) {
                        Button { showResetConfirm = true } label: {
                            HStack(spacing: 4) {
                                Image(systemName: "link.badge.minus")
                                    .font(.system(size: 10, weight: .bold))
                                Text("Reset Pairing")
                                    .font(.system(size: 11, weight: .bold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(Capsule().fill(Color.red.opacity(0.75)))
                        }
                        .buttonStyle(.plain)

                        Button { clipboardManager.clearHistory() } label: {
                            Text("Clear History")
                                .font(.system(size: 11, weight: .bold))
                                .foregroundColor(.black)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Capsule().fill(Color.white.opacity(0.7)))
                        }
                        .buttonStyle(.plain)
                    }
                    .padding(.trailing, 16)
                    .padding(.bottom, 16)
                }
            }
        }
        .frame(width: 345, height: 230)
    }
}

// MARK: - Nav Pill (Android LiquidGlassNavBar, exact animation)
// Sliding blue pill indicator + label expansion on active tab, matching
// the dampingRatio/stiffness values from AppFloatingToolbar.kt.

private struct NHSNavPill: View {
    let tabs: [MacNavigationTab]
    @Binding var selectedTab: MacNavigationTab

    @Namespace private var pillNamespace

    var body: some View {
        HStack(spacing: 0) {
            ForEach(tabs, id: \.self) { tab in
                let isSelected = selectedTab == tab

                HStack(spacing: 8) {
                    Image(systemName: tab.iconName)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(isSelected
                            ? .white
                            : Color(red: 0.29, green: 0.306, blue: 0.412)) // #4A4E69 from Android

                    if isSelected {
                        Text(tab.title)
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundColor(.white)
                            .fixedSize()
                            .transition(.opacity.combined(with: .scale(scale: 0.8,
                                                                        anchor: .leading)))
                    }
                }
                .padding(.horizontal, isSelected ? 20 : 16)
                .frame(height: 48)
                .background {
                    if isSelected {
                        // Sliding blue pill — Android #007AFF mapped to #4648D4
                        Capsule()
                            .fill(Color(red: 0.275, green: 0.282, blue: 0.831))
                            .matchedGeometryEffect(id: "NAV_PILL", in: pillNamespace)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture {
                    // Spring matching Android: dampingRatio=0.7, StiffnessLow
                    withAnimation(.spring(response: 0.3, dampingFraction: 1.0)) {
                        selectedTab = tab
                    }
                }
            }
        }
        .padding(8)
        // Pill container: rgba(255,255,255,0.6) background + white border from Figma
        .background(
            Capsule()
                .fill(Color.white.opacity(0.6))
                .shadow(color: Color(red: 0.275, green: 0.282, blue: 0.831).opacity(0.15),
                        radius: 24, x: 0, y: 8)
        )
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.8), lineWidth: 1)
        )
    }
}

// MARK: - Preview

#Preview {
    NewHomeScreen()
}
