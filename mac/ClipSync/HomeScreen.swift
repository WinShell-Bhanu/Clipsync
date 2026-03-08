// HomeScreen.swift
// Main settings + clipboard history screen shown after full onboarding.
// Displays paired device info, sync-direction toggles, an encryption test card,
// re-pair button, clipboard history list, and handles update alert presentation.

import SwiftUI
import AppKit
import CryptoKit
import Lottie

// MARK: - HomeScreen

struct HomeScreen: View {
    @StateObject private var clipboardManager = ClipboardManager.shared
    @StateObject private var pairingManager = PairingManager.shared

    @AppStorage("syncToMac") private var syncToMac = true
    @AppStorage("syncFromMac") private var syncFromMac = true

    @State private var encryptionTestResult: String? = nil
    @State private var isTestingEncryption = false
    @State private var showEncryptionSuccess = false
    @State private var hoveredClipboardItem: UUID? = nil

    @State private var contentOpacity: Double = 0
    @State private var contentOffset: CGFloat = 20
    @State private var tickID = UUID()
    @State private var navigateToRePair = false
    @State private var showResetConfirm = false

    @State private var showUpdateAlert = false
    @State private var updateInfo: UpdateNotificationManager.UpdateInfo? = nil

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {
            MeshBackground(introProgress: 1.0, shouldAnimate: true)
                .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 0) {


                Text("Settings")
                    .font(.system(size: 40, weight: .bold, design: .default))
                    .foregroundColor(.white)
                    .shadow(color: .black.opacity(0.2), radius: 10, x: 0, y: 4)
                    .shadow(color: .white.opacity(0.22), radius: 11, x: 0, y: 0)
                    .padding(.leading, 28)
                    .padding(.top, 50)
                    .padding(.bottom, 20)

                VStack(alignment: .leading, spacing: 16) {


                        HStack(alignment: .top, spacing: 18) {

                            VStack(alignment: .leading, spacing: 12) {

                                InnerGlassCard {
                                    HStack(spacing: 12) {
                                        Image("mobile")
                                            .resizable()
                                            .aspectRatio(contentMode: .fit)
                                            .frame(width: 28, height: 28)
                                            .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))

                                        Text(pairingManager.pairedDeviceName.isEmpty ? "No Device" : pairingManager.pairedDeviceName)
                                            .font(.system(size: 24, weight: .bold))
                                            .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
                                            .lineLimit(1)
                                            .minimumScaleFactor(0.5)

                                        Spacer()


                                        TickLottieView()
                                            .frame(width: 40, height: 40)
                                            .id(tickID)
                                    }
                                    .padding(.horizontal, 18)
                                    .padding(.vertical, 16)
                                }
                                .frame(width: 298, height: 70)
                                .offset(x: 7)


                                HStack(alignment: .top, spacing: 28) {

                                    InnerGlassCard {
                                        VStack(alignment: .leading, spacing: 0) {
                                            Text("Sync Settings")
                                                .font(.system(size: 11, weight: .semibold))
                                                .foregroundColor(.black.opacity(0.6))
                                                .padding(.top, 14)
                                                .padding(.leading, 12)
                                                .padding(.bottom, 12)


                                            HStack(spacing: 6) {
                                                Image(systemName: "iphone")
                                                    .font(.system(size: 14))
                                                    .foregroundColor(.black)
                                                    .frame(width: 16)

                                                Image(systemName: "arrow.right")
                                                    .font(.system(size: 10, weight: .bold))
                                                    .foregroundColor(.black.opacity(0.6))

                                                Image(systemName: "laptopcomputer")
                                                    .font(.system(size: 14))
                                                    .foregroundColor(.black)
                                                    .frame(width: 20)

                                                Spacer(minLength: 0)

                                                Toggle("", isOn: $syncToMac)
                                                    .labelsHidden()
                                                    .toggleStyle(.switch)
                                                    .scaleEffect(0.6)
                                            }
                                            .padding(.horizontal, 10)
                                            .padding(.bottom, 8)


                                            HStack(spacing: 6) {
                                                Image(systemName: "laptopcomputer")
                                                    .font(.system(size: 14))
                                                    .foregroundColor(.black)
                                                    .frame(width: 20)

                                                Image(systemName: "arrow.right")
                                                    .font(.system(size: 10, weight: .bold))
                                                    .foregroundColor(.black.opacity(0.6))

                                                Image(systemName: "iphone")
                                                    .font(.system(size: 14))
                                                    .foregroundColor(.black)
                                                    .frame(width: 16)

                                                Spacer(minLength: 0)

                                                Toggle("", isOn: $syncFromMac)
                                                    .labelsHidden()
                                                    .toggleStyle(.switch)
                                                    .scaleEffect(0.6)
                                            }
                                            .padding(.horizontal, 10)

                                            Spacer()
                                        }
                                    }
                                    .frame(width: 125, height: 130)


                                    ZStack {
                                        RePairButton {
                                            // Delete old Firestore doc, reset isPaired → false
                                            // (AppDelegate's $isPaired sink will auto-hide menu bar)
                                            pairingManager.clearPairing {
                                                navigateToRePair = true
                                            } onFailure: { _ in
                                                navigateToRePair = true
                                            }
                                        }
                                    }
                                    .frame(width: 138, height: 130)
                                }
                                .padding(.leading, 15)
                            }


                            CheckEncryptionCard(
                                encryptionTestResult: encryptionTestResult,
                                isTestingEncryption: isTestingEncryption,
                                showEncryptionSuccess: showEncryptionSuccess,
                                testAction: testEncryption
                            )
                            .frame(width: 200, height: 212)
                        }
                        .padding(.horizontal, 30)


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
                                                    withAnimation(.easeInOut(duration: 0.2)) {
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
                                        Button {
                                            showResetConfirm = true
                                        } label: {
                                            HStack(spacing: 4) {
                                                Image(systemName: "link.badge.minus")
                                                    .font(.system(size: 10, weight: .bold))
                                                Text("Reset Pairing")
                                                    .font(.system(size: 11, weight: .bold))
                                            }
                                            .foregroundColor(.white)
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 6)
                                            .background(
                                                Capsule()
                                                    .fill(Color.red.opacity(0.75))
                                            )
                                        }
                                        .buttonStyle(.plain)

                                        Button {
                                            clipboardManager.clearHistory()
                                        } label: {
                                            Text("Clear History")
                                                .font(.system(size: 11, weight: .bold))
                                                .foregroundColor(.black)
                                                .padding(.horizontal, 12)
                                                .padding(.vertical, 6)
                                                .background(
                                                    Capsule()
                                                        .fill(Color.white.opacity(0.7))
                                                )
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    .padding(.trailing, 16)
                                    .padding(.bottom, 16)
                                }
                            }
                        }
                        .frame(width: 520, height: 260)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.leading, 30)
                        .padding(.top, 8)

                        Spacer()
                            .frame(height: 10)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 0)
                .opacity(contentOpacity)
                .offset(y: contentOffset)
            }
            .frame(width: 590, height: 590)
        }
        .frame(width: 590, height: 590)
        .ignoresSafeArea()
        .navigationDestination(isPresented: Binding(
            get: { navigateToRePair },
            set: { if !$0 { navigateToRePair = false } }
        )) {
            QRGenScreen()
        }
        .onAppear {
            tickID = UUID()
            if !clipboardManager.isSyncPaused {
                clipboardManager.startMonitoring()
                clipboardManager.listenForAndroidClipboard()
            }
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
                contentOpacity = 1
                contentOffset = 0
            }
            
            // Check for pending update
            if let pending = UpdateNotificationManager.shared.getPendingUpdate() {
                updateInfo = pending
                showUpdateAlert = true
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .showUpdateDialog)) { _ in
            if let pending = UpdateNotificationManager.shared.getPendingUpdate() {
                updateInfo = pending
                showUpdateAlert = true
            }
        }
        .alert(isPresented: $showUpdateAlert) {
            Alert(
                title: Text("Update Available 🚀"),
                message: Text("Version \(updateInfo?.version ?? "Unknown") is now available!\n\n\(updateInfo?.releaseNotes ?? "")"),
                primaryButton: .default(Text("Download")) {
                    if let urlString = updateInfo?.downloadUrl,
                       let url = URL(string: urlString) {
                        NSWorkspace.shared.open(url)
                    }
                    UpdateNotificationManager.shared.clearPendingUpdate()
                },
                secondaryButton: .cancel(Text("Later")) {
                    UpdateNotificationManager.shared.clearPendingUpdate()
                }
            )
        }
        .alert("Reset Pairing?", isPresented: $showResetConfirm) {
            Button("Reset", role: .destructive) {
                pairingManager.clearPairing()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will disconnect your Android device and delete all pairing data. You'll need to scan the QR code again to reconnect.")
        }
        .enableInjection()
    }


    // MARK: - Encryption Test

    /// Runs a round-trip AES-GCM encrypt/decrypt test on a random string and
    /// shows a success animation or an error message depending on the result.
    private func testEncryption() {
        isTestingEncryption = true
        encryptionTestResult = nil
        showEncryptionSuccess = false

        DispatchQueue.global(qos: .userInitiated).async {
            let testString = "ClipSync Encryption Test - \(UUID().uuidString)"
            let encrypted = encryptTestString(testString)
            let decrypted = decryptTestString(encrypted)

            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                isTestingEncryption = false

                if let decrypted = decrypted, decrypted == testString {

                    showEncryptionSuccess = true

                    DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                        showEncryptionSuccess = false
                    }
                } else {
                    encryptionTestResult = " Encryption test failed"
                }
            }
        }
    }


    private func encryptTestString(_ string: String) -> String {
        guard let data = string.data(using: .utf8) else { return "" }
        let sharedSecretHex = Secrets.fallbackEncryptionKey

        do {
            let keyData = hexToData(hex: sharedSecretHex)
            let key = SymmetricKey(data: keyData)
            let sealedBox = try AES.GCM.seal(data, using: key)
            return sealedBox.combined?.base64EncodedString() ?? ""
        } catch {
            return ""
        }
    }


    private func decryptTestString(_ base64String: String) -> String? {
        guard let data = Data(base64Encoded: base64String) else { return nil }
        let sharedSecretHex = Secrets.fallbackEncryptionKey

        do {
            let keyData = hexToData(hex: sharedSecretHex)
            let key = SymmetricKey(data: keyData)
            let sealedBox = try AES.GCM.SealedBox(combined: data)
            let decryptedData = try AES.GCM.open(sealedBox, using: key)
            return String(data: decryptedData, encoding: .utf8)
        } catch {
            return nil
        }
    }


    private func hexToData(hex: String) -> Data {
        var data = Data()
        var temp = ""
        for char in hex {
            temp.append(char)
            if temp.count == 2 {
                if let byte = UInt8(temp, radix: 16) {
                    data.append(byte)
                }
                temp = ""
            }
        }
        return data
    }
}

// MARK: - Supporting Views

/// Frosted-glass card container used throughout HomeScreen.
struct InnerGlassCard<Content: View>: View {
    let content: Content


    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white.opacity(0.4))
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Color.white.opacity(0.3), lineWidth: 0.5)
                )
                .shadow(color: Color.black.opacity(0.1), radius: 8, x: 0, y: 4)

            content
        }
    }
}


/// NSViewRepresentable wrapper that plays the `tick` Lottie animation once.
struct TickLottieView: NSViewRepresentable {


    func makeNSView(context: Context) -> NSView {
        let containerView = NSView(frame: .zero)
        containerView.wantsLayer = true
        containerView.layer?.masksToBounds = true

        let animationView = LottieAnimationView(name: "tick")
        animationView.contentMode = .scaleAspectFit
        animationView.loopMode = .playOnce
        animationView.animationSpeed = 1.0
        animationView.backgroundBehavior = .pauseAndRestore
        animationView.autoresizingMask = [.width, .height]
        animationView.translatesAutoresizingMaskIntoConstraints = true

        containerView.addSubview(animationView)
        animationView.play()

        return containerView
    }


    func updateNSView(_ nsView: NSView, context: Context) {
        if let animationView = nsView.subviews.first as? LottieAnimationView {
            animationView.frame = nsView.bounds
        }
    }
}


/// NSViewRepresentable wrapper that plays the `Loading` Lottie animation on loop.
struct LoadingLottieView: NSViewRepresentable {


    func makeNSView(context: Context) -> NSView {
        let containerView = NSView(frame: .zero)
        containerView.wantsLayer = true
        containerView.layer?.masksToBounds = true

        let animationView = LottieAnimationView(name: "Loading")
        animationView.contentMode = .scaleAspectFit
        animationView.loopMode = .loop
        animationView.animationSpeed = 1.0
        animationView.backgroundBehavior = .pauseAndRestore
        animationView.autoresizingMask = [.width, .height]
        animationView.translatesAutoresizingMaskIntoConstraints = true

        containerView.addSubview(animationView)
        animationView.play()

        return containerView
    }


    func updateNSView(_ nsView: NSView, context: Context) {
        if let animationView = nsView.subviews.first as? LottieAnimationView {
            animationView.frame = nsView.bounds
        }
    }
}


/// NSViewRepresentable wrapper that plays the `lock` Lottie animation once on appear.
struct LockLottieView: NSViewRepresentable {


    func makeNSView(context: Context) -> NSView {
        let containerView = NSView(frame: .zero)
        containerView.wantsLayer = true
        containerView.layer?.masksToBounds = true

        let animationView = LottieAnimationView(name: "lock")
        animationView.contentMode = .scaleAspectFit
        animationView.loopMode = .playOnce
        animationView.animationSpeed = 1.0
        animationView.backgroundBehavior = .pauseAndRestore
        animationView.autoresizingMask = [.width, .height]
        animationView.translatesAutoresizingMaskIntoConstraints = true

        containerView.addSubview(animationView)
        animationView.play()

        return containerView
    }


    func updateNSView(_ nsView: NSView, context: Context) {
        if let animationView = nsView.subviews.first as? LottieAnimationView {
            animationView.frame = nsView.bounds
        }
    }
}


/// Compact button that triggers re-pairing; shows a QR icon on hover.
struct RePairButton: View {
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


/// Card that lets the user manually trigger an AES-GCM round-trip test to
/// verify the shared encryption key is working correctly.
struct CheckEncryptionCard: View {
    let encryptionTestResult: String?
    let isTestingEncryption: Bool
    let showEncryptionSuccess: Bool
    let testAction: () -> Void

    @State private var isHovered = false

    var body: some View {
        InnerGlassCard {
            ZStack {

                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top, spacing: 12) {
                        Text("Check\nEncryption")
                            .font(.system(size: 22, weight: .bold))
                            .lineLimit(2)
                            .minimumScaleFactor(0.5)
                            .foregroundColor(.black)
                            .fixedSize(horizontal: false, vertical: true)

                        Spacer()

                        if #available(macOS 14.0, *) {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.black)
                                .symbolEffect(.variableColor.iterative, options: .nonRepeating)
                        } else {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 28))
                                .foregroundColor(.black)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 20)
                    .opacity(showEncryptionSuccess ? 0 : 1)

                    Spacer()


                    if let result = encryptionTestResult, !result.contains("") {
                         HStack {
                            Image(systemName: "xmark.square.fill")
                                .foregroundColor(.red)
                            Text(result.replacingOccurrences(of: " ", with: ""))
                                .font(.system(size: 12))
                                .foregroundColor(.black.opacity(0.8))
                        }
                        .padding(.horizontal, 20)
                        .padding(.bottom, 8)
                    }


                    if isHovered || isTestingEncryption {
                        Button {
                            testAction()
                        } label: {
                            HStack(spacing: 6) {
                                if isTestingEncryption {
                                    LoadingLottieView()
                                        .frame(width: 18, height: 18)
                                    Text("Testing...")
                                        .font(.system(size: 13, weight: .medium))
                                } else {
                                    Image(systemName: "lock.fill")
                                        .font(.system(size: 12))
                                    Text("Test Now")
                                        .font(.system(size: 13, weight: .medium))
                                }
                            }
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 36)
                            .background(Color.black)
                            .cornerRadius(18)
                        }
                        .buttonStyle(.plain)
                        .disabled(isTestingEncryption)
                        .padding(.horizontal, 20)
                        .padding(.bottom, 20)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .opacity(showEncryptionSuccess ? 0 : 1)
                    } else {
                        Spacer()
                            .frame(height: 56)
                    }
                }


                if showEncryptionSuccess {
                    LockLottieView()
                        .frame(width: 150, height: 150)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
        .scaleEffect(isHovered ? 1.03 : 1.0)
        .animation(.spring(response: 0.3, dampingFraction: 0.6), value: isHovered)
        .onHover { hovering in
            withAnimation(.easeInOut(duration: 0.2)) {
                isHovered = hovering
            }
        }
    }
}


/// Single row in the clipboard history list. Content is masked until hovered.
struct ClipboardHistoryRow: View {
    let item: ClipboardItem
    let isHovered: Bool

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

                if isHovered {
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

#Preview {
    HomeScreen()
}

