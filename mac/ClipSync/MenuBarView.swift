// MenuBarView.swift
// Popover content shown when the user clicks the menu bar icon.
// V2 design: header with live transfer status + speed, last-synced items,
// sync-active toggle, send-file button, and footer actions.

import SwiftUI
import LocalAuthentication
import UniformTypeIdentifiers

// MARK: - MenuBarView

struct MenuBarView: View {
    @Environment(\.openWindow) var openWindow
    @ObservedObject private var pairingManager   = PairingManager.shared
    @ObservedObject private var clipboardManager = ClipboardManager.shared
    @StateObject private var qrGenerator      = QRCodeGenerator.shared
    @StateObject private var server           = ClipSyncServer.shared
    @ObservedObject private var updateManager    = MacUpdateManager.shared

    @AppStorage("UltraFastTransfer") private var isUltraFastTransfer: Bool = false

    /// Set to `true` in `#Preview` to show the full paired UI without a real device.
    var previewMode: Bool = false

    @State private var showingRePairQR  = false
    @State private var isAuthenticating = false

    @State private var showingUltraFastWarning = false

    #if DEBUG
    #endif

    var body: some View {
        VStack(spacing: 0) {
            if showingRePairQR {
                rePairQRView
            } else {
                mainView
            }
        }
        .alert("Ultra Fast Transfer (Unencrypted)", isPresented: $showingUltraFastWarning) {
            Button("Cancel", role: .cancel) {
                isUltraFastTransfer = false
                WakeupReceiver.shared.pushToAndroid(json: ["type": "setting", "ultra_fast": false])
            }
            Button("Enable", role: .destructive) {
                isUltraFastTransfer = true
                WakeupReceiver.shared.pushToAndroid(json: ["type": "setting", "ultra_fast": true])
            }
        } message: {
            Text("This will disable encryption for large file transfers to maximize speed. Only use this on a trusted home Wi-Fi network.")
        }
    }

    // MARK: - Re-pair QR View

    private var rePairQRView: some View {
        VStack(spacing: 20) {
            Text("Scan to connect")
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(.primary)
                .padding(.top, 20)

            if let qrImage = qrGenerator.qrImage {
                Image(nsImage: qrImage)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 160, height: 160)
                    .padding(12)
                    .background(Color.white)
                    .cornerRadius(16)
                    .shadow(radius: 4)
            } else {
                ProgressView().frame(width: 160, height: 160)
            }

            Text(DeviceManager.shared.getFriendlyMacName())
                .font(.system(size: 12))
                .foregroundColor(.secondary)

            Button("Cancel") {
                withAnimation(.spring(response: 0.3, dampingFraction: 1.0)) { showingRePairQR = false }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .padding(.bottom, 20)
        }
        .frame(width: 280)
        .background(EffectView(material: .menu, blendingMode: .behindWindow))
        .onAppear {
            qrGenerator.generateQRCode()
            pairingManager.listenForPairing(macDeviceId: DeviceManager.shared.getDeviceId())
        }
        .onDisappear { pairingManager.stopListening() }
    }

    // MARK: - Main View

    private var mainView: some View {
        VStack(spacing: 0) {

            // ── Header: status dot + device name + subtitle ──
            HStack(spacing: 12) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)
                    .shadow(color: statusColor.opacity(0.5), radius: 4)

                VStack(alignment: .leading, spacing: 2) {
                    Text(isPaired ? deviceDisplayName : "Not Connected")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundColor(.primary)

                    if (isReceiving || server.isSendingFile) && !server.transferSpeedString.isEmpty {
                        Text(server.isSendingFile ? "Sending • " : "Receiving • ")
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                        + Text(server.transferSpeedString)
                            .font(.system(size: 11))
                            .foregroundColor(.green)
                    } else {
                        Text(statusSubtitle)
                            .font(.system(size: 11))
                            .foregroundColor(.secondary)
                    }
                }

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

            // ── Live Transfer Banner (shown during active send or receive) ──
            if isReceiving || server.isSendingFile {
                transferBanner
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            Divider()
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .opacity(0.5)

            // ── Last Synced Items ──
            VStack(alignment: .leading, spacing: 8) {
                Text("LAST SYNCED")
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundColor(.secondary)
                    .tracking(0.5)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)

                if clipboardManager.history.isEmpty {
                    Text("Nothing synced yet")
                        .font(.system(size: 12))
                        .foregroundColor(.secondary)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 4)
                } else {
                    VStack(spacing: 2) {
                        ForEach(clipboardManager.history.prefix(2)) { item in
                            let recentItem = RecentItem(
                                icon: item.isImage ? "photo" : (item.content.hasPrefix("http") ? "link" : "doc.text"),
                                label: item.isImage ? "Image" : item.content,
                                time: item.timeAgo
                            )
                            RecentItemRow(item: recentItem) {
                                if item.isFile || item.isImage {
                                    if let path = item.filePath {
                                        let fileUrl = URL(fileURLWithPath: path)
                                        var isDir: ObjCBool = false
                                        if FileManager.default.fileExists(atPath: fileUrl.path, isDirectory: &isDir) {
                                            NSWorkspace.shared.activateFileViewerSelecting([fileUrl])
                                            return
                                        }
                                    } else if item.direction == .received {
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
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(item.content, forType: .string)
                            }
                        }
                    }
                }
            }

            Divider()
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .opacity(0.5)

            // ── Sync Active Toggle ──
            HStack(spacing: 8) {
                Image(systemName: clipboardManager.isSyncPaused ? "pause.circle" : "bolt.fill")
                    .font(.system(size: 12))
                    .foregroundColor(.accentColor)
                    .frame(width: 16)

                Text(clipboardManager.isSyncPaused ? "Sync Paused" : "Sync Active")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.primary)

                Spacer()

                Toggle("", isOn: Binding(
                    get: { !clipboardManager.isSyncPaused },
                    set: { _ in clipboardManager.toggleSync() }
                ))
                .toggleStyle(.switch)
                .controlSize(.small)
                .labelsHidden()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)

            // ── Ultra Fast Toggle ──
            HStack(spacing: 8) {
                Image(systemName: "hare.fill")
                    .font(.system(size: 12))
                    .foregroundColor(isUltraFastTransfer ? .orange : .gray)
                    .frame(width: 16)

                Text("Ultra Fast Transfer")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundColor(.primary)

                Spacer()

                Toggle("", isOn: Binding(
                    get: { isUltraFastTransfer },
                    set: { newValue in
                        if newValue {
                            showingUltraFastWarning = true
                        } else {
                            isUltraFastTransfer = false
                            WakeupReceiver.shared.pushToAndroid(json: ["type": "setting", "ultra_fast": false])
                        }
                    }
                ))
                .toggleStyle(.switch)
                .controlSize(.small)
                .labelsHidden()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)


            // ── Send File Button ──
            if isPaired {
                sendFileButton
                    .padding(.horizontal, 16)
                    .padding(.bottom, 10)
            }

            // ── Check for Updates ──
            Button(action: {
                UpdateWindowController.shared.showWindow()
                updateManager.checkForUpdate(manual: true)
            }) {
                HStack(spacing: 8) {
                    Image(systemName: "arrow.triangle.2.circlepath")
                        .font(.system(size: 12))
                        .foregroundColor(.gray)
                        .frame(width: 16)
                    
                    Text("Check for Updates")
                        .font(.system(size: 12, weight: .medium))
                        .foregroundColor(.primary)
                    
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.bottom, 10)

            Divider()
                .padding(.horizontal, 16)
                .opacity(0.5)

            // ── Footer Actions ──
            HStack {
                Button(action: openDashboard) {
                    Label("Dashboard", systemImage: "square.grid.2x2")
                        .labelStyle(FooterLabelStyle())
                }
                .buttonStyle(.plain)

                Spacer()

                Button(action: authenticateUser) {
                    Label("Re-pair", systemImage: "qrcode")
                        .labelStyle(FooterLabelStyle())
                }
                .buttonStyle(.plain)
                .disabled(isAuthenticating)

                Spacer()

                Button(action: { NSApplication.shared.terminate(nil) }) {
                    Label("Quit", systemImage: "power")
                        .labelStyle(FooterLabelStyle(isDestructive: true))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .frame(width: 280)
        .background(EffectView(material: .popover, blendingMode: .behindWindow))
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: isReceiving)
        .animation(.spring(response: 0.3, dampingFraction: 1.0), value: server.isSendingFile)
    }

    // MARK: - Transfer Banner (receive & send progress)

    private var transferBanner: some View {
        let isSending = server.isSendingFile
        let iconName = isSending ? "arrow.up.circle.fill" : "arrow.down.circle.fill"
        let iconColor: Color = isSending ? .accentColor : .green
        let title = server.currentTransferFileName ?? (isSending ? "Sending…" : "Receiving…")
        let progress = isSending ? server.sendFileProgress : server.transferProgress
        let totalBytes = server.transferTotalBytes
        let bytesDone = isSending ? Int64(Double(totalBytes) * server.sendFileProgress) : server.bytesReceived
        
        return VStack(spacing: 4) {
            HStack(spacing: 6) {
                Image(systemName: iconName)
                    .font(.system(size: 11))
                    .foregroundColor(iconColor)

                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Spacer()

                if totalBytes > 0 {
                    let rMB = Double(bytesDone) / 1_048_576.0
                    let tMB = Double(totalBytes) / 1_048_576.0
                    Text(String(format: "%.1f / %.1f MB", rMB, tMB))
                        .font(.system(size: 10).monospacedDigit())
                        .foregroundColor(.secondary)
                } else {
                    Text("\(Int(progress * 100))%")
                        .font(.system(size: 10).monospacedDigit())
                        .foregroundColor(.secondary)
                }

                Button(action: { isSending ? server.cancelSend() : server.cancelReceive() }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.secondary)
                }
                .buttonStyle(.plain)
            }

            ProgressView(value: progress)
                .progressViewStyle(.linear)
                .tint(iconColor)
                
            if !isUltraFastTransfer {
                ShimmeringEncryptionBadge()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
    }

    // MARK: - Send File Button

    private var sendFileButton: some View {
        Group {
            if !server.isSendingFile {
                Button(action: {
                    guard !previewMode else { return }
                    let panel = NSOpenPanel()
                    panel.allowsMultipleSelection = true
                    panel.canChooseFiles = true
                    panel.canChooseDirectories = false
                    panel.allowedContentTypes = [.item]
                    panel.title = "Choose files to send"
                    panel.center()
                    panel.makeKeyAndOrderFront(nil)
                    NSApp.activate(ignoringOtherApps: true)
                    if panel.runModal() == .OK {
                        server.sendFiles(urls: panel.urls)
                    }
                }) {

                    HStack(spacing: 8) {
                        Image(systemName: "arrow.up.doc.fill")
                            .font(.system(size: 12))
                            .foregroundColor(.accentColor)
                        Text("Send File to \(deviceDisplayName.components(separatedBy: " ").prefix(2).joined(separator: " "))")
                            .font(.system(size: 12, weight: .medium))
                            .foregroundColor(.primary)
                            .lineLimit(1)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10))
                            .foregroundColor(.secondary)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(
                        RoundedRectangle(cornerRadius: 10)
                            .fill(Color.primary.opacity(0.05))
                            .overlay(
                                RoundedRectangle(cornerRadius: 10)
                                    .strokeBorder(Color.accentColor.opacity(0.25), lineWidth: 1)
                            )
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    // MARK: - Preview helpers

    /// True when a real device is paired, OR when running in Canvas preview mode.
    private var isPaired: Bool { previewMode || pairingManager.isPaired }

    /// Device name shown in UI — uses a placeholder in preview.
    private var deviceDisplayName: String {
        previewMode ? "Bhanu's Pixel 8" : (pairingManager.pairedDeviceName.isEmpty ? "Android Device" : pairingManager.pairedDeviceName)
    }

    // MARK: - Computed Properties

    var isReceiving: Bool {
        server.transferProgress > 0 && server.transferProgress < 1.0
    }

    var statusColor: Color {
        if !isPaired { return .secondary }
        if server.isSendingFile { return .accentColor }
        if isReceiving { return .green }
        return clipboardManager.isSyncPaused ? .orange : .green
    }

    var statusSubtitle: String {
        if !isPaired { return "No device paired" }
        if isReceiving {
            let speed = server.transferSpeedString.isEmpty ? "" : " • \(server.transferSpeedString)"
            return "Receiving\(speed)"
        }
        if server.isSendingFile {
            let speed = server.transferSpeedString.isEmpty ? "" : " • \(server.transferSpeedString)"
            return "Sending\(speed)"
        }
        if clipboardManager.isSyncPaused { return "Sync is paused" }
        guard let date = clipboardManager.lastSyncedTime else { return "Connected • Ready" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return "Synced " + formatter.localizedString(for: date, relativeTo: Date())
    }



    // MARK: - Actions

    func openDashboard() {
        if let window = NSApp.windows.first(where: { $0.identifier?.rawValue == "mainWindow" }) {
            window.makeKeyAndOrderFront(nil)
        } else {
            openWindow(id: "main")
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func authenticateUser() {
        if isAuthenticating { return }
        isAuthenticating = true

        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Authenticate to re-pair") { success, _ in
                DispatchQueue.main.async {
                    self.isAuthenticating = false
                    if success { self.startRePair() }
                }
            }
        } else {
            DispatchQueue.main.async {
                self.isAuthenticating = false
                self.startRePair()
            }
        }
    }

    private func startRePair() {
        withAnimation(.spring(response: 0.3, dampingFraction: 1.0)) {
            pairingManager.clearPairing(
                onSuccess: { self.showingRePairQR = true },
                onFailure: { _ in self.showingRePairQR = true }
            )
        }
    }
}

// MARK: - Recent Item Model

struct RecentItem: Identifiable {
    let id    = UUID()
    let icon:  String
    let label: String
    let time:  String
}

// MARK: - Recent Item Row

struct RecentItemRow: View {
    let item:   RecentItem
    let action: () -> Void

    @State private var isHovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: item.icon)
                    .font(.system(size: 12))
                    .foregroundColor(.secondary)
                    .frame(width: 16)

                Text(item.label)
                    .font(.system(size: 12))
                    .foregroundColor(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Spacer()

                Text(item.time)
                    .font(.system(size: 11))
                    .foregroundColor(.secondary)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 6)
            .background(
                Rectangle()
                    .fill(Color.primary.opacity(isHovering ? 0.06 : 0))
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
    }
}

// MARK: - Footer Label Style

struct FooterLabelStyle: LabelStyle {
    var isDestructive: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) {
            configuration.icon
                .font(.system(size: 10))
            configuration.title
                .font(.system(size: 11))
        }
        .foregroundColor(isDestructive ? .red : .secondary)
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
        .contentShape(Rectangle())
    }
}

// MARK: - Visual Effect Background

struct EffectView: NSViewRepresentable {
    var material:     NSVisualEffectView.Material
    var blendingMode: NSVisualEffectView.BlendingMode

    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material     = material
        view.blendingMode = blendingMode
        view.state        = .active
        return view
    }

    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material     = material
        nsView.blendingMode = blendingMode
    }
}

// MARK: - Shimmering Encryption Badge

struct ShimmeringEncryptionBadge: View {
    @State private var isAnimating = false
    
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "lock.fill")
            Text("End to End Encrypted")
        }
        .font(.system(size: 13, weight: .medium))
        .foregroundColor(.gray)
        .frame(maxWidth: .infinity, alignment: .center)
        .overlay(
            LinearGradient(
                gradient: Gradient(colors: [.clear, Color.white.opacity(0.5), .clear]),
                startPoint: .leading,
                endPoint: .trailing
            )
            .rotationEffect(.degrees(30))
            .offset(x: isAnimating ? 250 : -250) // Increased offset since it spans infinity
            .mask(
                HStack(spacing: 4) {
                    Image(systemName: "lock.fill")
                    Text("End to End Encrypted")
                }
                .font(.system(size: 13, weight: .medium))
                .frame(maxWidth: .infinity, alignment: .center)
            )
        )
        .onAppear {
            withAnimation(Animation.linear(duration: 2.5).repeatForever(autoreverses: false)) {
                isAnimating = true
            }
        }
        .padding(.top, 2)
    }
}

#Preview {
    MenuBarView(previewMode: true)
}
