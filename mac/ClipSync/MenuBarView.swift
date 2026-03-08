// MenuBarView.swift
// Popover content shown when the user clicks the menu bar icon.
// Shows connection status, last sync time, Send/Pull action buttons, and footer
// actions. Re-pair flow: biometric auth → clearPairing → inline QR code.

import SwiftUI
import LocalAuthentication

// MARK: - MenuBarView

struct MenuBarView: View {
    @Environment(\.openWindow) var openWindow
    @StateObject private var pairingManager = PairingManager.shared
    @StateObject private var clipboardManager = ClipboardManager.shared
    @StateObject private var qrGenerator = QRCodeGenerator.shared


    @State private var showingRePairQR = false
    @State private var isHoveringSend = false
    @State private var isHoveringPull = false
    @State private var isHoveringSettings = false
    @State private var isHoveringQuit = false
    @State private var isAuthenticating = false

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        VStack(spacing: 0) {
            if showingRePairQR {
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
                        withAnimation(.spring()) { showingRePairQR = false }
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

            } else {
                VStack(spacing: 16) {

                    HStack(spacing: 12) {

                        Circle()
                            .fill(connectionStatusColor)
                            .frame(width: 8, height: 8)
                            .shadow(color: connectionStatusColor.opacity(0.5), radius: 4)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(pairingManager.isPaired ? pairingManager.pairedDeviceName : "Not Connected")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundColor(.primary)

                            if pairingManager.isPaired {
                                Text(lastSyncedText)
                                    .font(.system(size: 11))
                                    .foregroundColor(.secondary)
                            }
                        }

                        Spacer()


                        Button(action: { clipboardManager.toggleSync() }) {
                            Image(systemName: clipboardManager.isSyncPaused ? "pause.circle.fill" : "play.circle.fill")
                                .font(.system(size: 20))
                                .foregroundColor(clipboardManager.isSyncPaused ? .secondary : .accentColor)
                        }
                        .buttonStyle(.plain)
                        .help(clipboardManager.isSyncPaused ? "Resume Sync" : "Pause Sync")
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 16)

                    Divider()
                        .padding(.horizontal, 16)
                        .opacity(0.5)


                    HStack(spacing: 12) {

                        MenuActionButton(
                            title: "Send",
                            icon: "arrow.up.circle",
                            color: .blue,
                            isHovering: $isHoveringSend
                        ) {
                            clipboardManager.startMonitoring()
                        }


                        MenuActionButton(
                            title: "Pull",
                            icon: "arrow.down.circle",
                            color: .purple,
                            isHovering: $isHoveringPull
                        ) {
                            clipboardManager.pullClipboard()
                        }
                    }
                    .padding(.horizontal, 16)


                    HStack {
                        Button(action: {
                            if let window = NSApp.windows.first(where: { $0.identifier?.rawValue == "mainWindow" }) {
                                window.makeKeyAndOrderFront(nil)
                                NSApp.activate(ignoringOtherApps: true)
                            } else {
                                openWindow(id: "main")
                                NSApp.activate(ignoringOtherApps: true)
                            }
                        }) {
                            Label("Settings", systemImage: "gearshape")
                                .labelStyle(FooterLabelStyle())
                        }
                        .buttonStyle(.plain)

                        Spacer()

                        Button(action: {
                            authenticateUser()
                        }) {
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
                    .padding(.bottom, 12)
                }
                .frame(width: 280)
                .background(EffectView(material: .popover, blendingMode: .behindWindow))
            }
        }
        .enableInjection()
    }


    // MARK: - Computed Properties

    /// Returns green when syncing, orange when paused, secondary when unpaired.
    var connectionStatusColor: Color {
        if !pairingManager.isPaired { return .secondary }
        return clipboardManager.isSyncPaused ? .orange : .green
    }

    /// Relative time string from the last successful clipboard sync.
    var lastSyncedText: String {
        guard let date = clipboardManager.lastSyncedTime else { return "Ready to sync" }
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return "Synced " + formatter.localizedString(for: date, relativeTo: Date())
    }

    // MARK: - Actions

    /// Prompts Touch ID / password before initiating the clearPairing flow.
    /// Falls back to clearing directly if biometrics are unavailable.
    func authenticateUser() {
        if isAuthenticating { return }
        isAuthenticating = true

        let context = LAContext()
        var error: NSError?

        if context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) {
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: "Authenticate to re-pair") { success, _ in
                DispatchQueue.main.async {
                    self.isAuthenticating = false

                    if success {
                        withAnimation {
                            self.pairingManager.clearPairing(
                                onSuccess: {
                                    self.showingRePairQR = true
                                },
                                onFailure: { error in
                                    self.showingRePairQR = true
                                }
                            )
                        }
                    }
                }
            }
        } else {
            DispatchQueue.main.async {
                self.isAuthenticating = false
                withAnimation {
                    self.pairingManager.clearPairing(
                        onSuccess: {
                            self.showingRePairQR = true
                        },
                        onFailure: { _ in
                            self.showingRePairQR = true
                        }
                    )
                }
            }
        }
    }
}


// MARK: - Supporting Views

/// Reusable icon + label tile used in the Send/Pull action grid.
struct MenuActionButton: View {
    let title: String
    let icon: String
    let color: Color
    @Binding var isHovering: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 22, weight: .medium))
                    .foregroundColor(color)

                Text(title)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundColor(.primary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color.primary.opacity(isHovering ? 0.08 : 0.03))
            )
        }
        .buttonStyle(.plain)
        .onHover { isHovering = $0 }
    }
}


/// Compact icon + label style used in the footer action buttons.
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


/// NSViewRepresentable wrapper for NSVisualEffectView to provide vibrancy backgrounds.
struct EffectView: NSViewRepresentable {
    var material: NSVisualEffectView.Material
    var blendingMode: NSVisualEffectView.BlendingMode


    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.material = material
        view.blendingMode = blendingMode
        view.state = .active
        return view
    }


    func updateNSView(_ nsView: NSVisualEffectView, context: Context) {
        nsView.material = material
        nsView.blendingMode = blendingMode
    }
}

#Preview {
    MenuBarView()
}
