// Final.swift (FinalScreen)
// Onboarding permissions screen shown after the connected animation.
// Requests Accessibility and Notification permissions; Network Access is always enabled.
// Polls permission state every 1 second so toggles update live without a relaunch.
// "Finish Setup" calls PairingManager.shared.completeSetup() to transition to HomeScreen.

import SwiftUI
import AppKit
import UserNotifications


// MARK: - FinalScreen

struct FinalScreen: View {

    @State private var showAccessibilityIcon = false
    @State private var showNetworkIcon = false
    @State private var showNotificationIcon = false


    @State private var titleOpacity: Double = 0
    @State private var titleOffset: CGFloat = -30
    @State private var subtitleOpacity: Double = 0
    @State private var subtitleOffset: CGFloat = -20
    @State private var cardsOpacity: Double = 0
    @State private var cardsOffset: CGFloat = 50
    @State private var buttonOpacity: Double = 0
    @State private var buttonScale: CGFloat = 0.8


    @State private var isAccessibilityGranted = false
    @State private var isNotificationsGranted = false
    @State private var networkEnabled = true


    @State private var accessibilityIntent = false
    @State private var notificationIntent = false


    @State private var permissionTimer: Timer?

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack(alignment: .topLeading) {

            MeshBackground()
                .ignoresSafeArea(.all)


            Text("Almost there. Just Need a few\npermissions")
                .font(.custom("SF Pro Display", size: 40))
                .fontWeight(.bold)
                .kerning(-1.2)
                .lineSpacing(8)
                .foregroundColor(.white)
                .padding(.top, 80)
                .offset(x: 70, y: 25 + titleOffset)
                .opacity(titleOpacity)


            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.white.opacity(0.15))
                .frame(width: 520, height: 600)
                .shadow(color: Color.black.opacity(0.1), radius: 10, x: 0, y: 4)
                .offset(x: 90, y: 215 + cardsOffset)
                .opacity(cardsOpacity)


            Text("To keep ClipSync working smoothly, allow these\npermissions")
                .font(.custom("SF Pro", size: 24))
                .fontWeight(.medium)
                .kerning(-0.66)
                .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
                .multilineTextAlignment(.center)
                .offset(x: 110, y: 230 + subtitleOffset)
                .opacity(subtitleOpacity)


            HStack(spacing: 10) {
                if showAccessibilityIcon {
                    if #available(macOS 15.0, *) {
                        Image(systemName: "accessibility")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 0.0, green: 0.478, blue: 1.0))
                            .symbolEffect(.breathe.pulse.byLayer, options: .repeat(.periodic(delay: 2.0)))
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    } else {
                         Image(systemName: "accessibility")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 0.0, green: 0.478, blue: 1.0))
                            .symbolEffect(.variableColor.iterative, options: .repeating)
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text("Accessibility")
                        .font(.custom("SF Pro", size: 15))
                        .fontWeight(.medium)
                        .foregroundColor(.black)

                    Text("Required so ClipSync can securely read and sync your copied text in the background.")
                        .font(.custom("SF Pro Display", size: 12))
                        .foregroundColor(Color(red: 0.314, green: 0.286, blue: 0.286))
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Toggle("", isOn: Binding(
                    get: { isAccessibilityGranted || accessibilityIntent },
                    set: { newValue in
                        if newValue && !isAccessibilityGranted {
                            accessibilityIntent = true
                            requestAccessibilityPermission()
                        } else if !newValue && isAccessibilityGranted {
                            openSystemSettings()
                        }
                    }
                ))
                .labelsHidden()
                .toggleStyle(.switch)
                .fixedSize()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(width: 480, height: 70)
            .background(
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .fill(Color.white.opacity(0.6))
            )
            .offset(x: 105, y: 320 + cardsOffset)
            .opacity(cardsOpacity)


            HStack(spacing: 10) {
                if showNetworkIcon {
                    if #available(macOS 15.0, *) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 0.204, green: 0.780, blue: 0.349))
                            .symbolEffect(.bounce.down.byLayer, options: .repeat(.periodic(delay: 2.0)))
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    } else {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 0.204, green: 0.780, blue: 0.349))
                            .symbolEffect(.variableColor.iterative, options: .repeating)
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text("Network Access")
                        .font(.custom("SF Pro", size: 15))
                        .fontWeight(.medium)
                        .foregroundColor(.black)

                    Text("Allows your Mac to stay linked with your phone for realtime sync.")
                        .font(.custom("SF Pro Display", size: 12))
                        .foregroundColor(Color(red: 0.314, green: 0.286, blue: 0.286))
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Toggle("", isOn: $networkEnabled)
                    .labelsHidden()
                    .toggleStyle(.switch)
                    .disabled(true)
                    .fixedSize()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(width: 480, height: 70)
            .background(
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .fill(Color.white.opacity(0.6))
            )
            .offset(x: 105, y: 420 + cardsOffset)
            .opacity(cardsOpacity)


            HStack(spacing: 10) {
                if showNotificationIcon {
                    if #available(macOS 15.0, *) {
                        Image(systemName: "bell.badge")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 1.0, green: 0.231, blue: 0.188))
                            .symbolEffect(.wiggle.byLayer, options: .repeat(.periodic(delay: 2.0)))
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    } else {
                        Image(systemName: "bell.badge")
                            .font(.system(size: 26, weight: .medium))
                            .foregroundStyle(Color(red: 1.0, green: 0.231, blue: 0.188))
                            .symbolEffect(.variableColor.iterative, options: .repeating)
                            .frame(width: 30)
                            .transition(.scale.combined(with: .opacity))
                    }
                }

                VStack(alignment: .leading, spacing: 3) {
                    Text("Notifications")
                        .font(.custom("SF Pro", size: 15))
                        .fontWeight(.medium)
                        .foregroundColor(.black)

                    Text("So we can let you know if sync is paused, or when new updates and features arrive.")
                        .font(.custom("SF Pro Display", size: 12))
                        .foregroundColor(Color(red: 0.314, green: 0.286, blue: 0.286))
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Toggle("", isOn: Binding(
                    get: { isNotificationsGranted || notificationIntent },
                    set: { newValue in
                        if newValue && !isNotificationsGranted {
                            notificationIntent = true
                            requestNotificationPermission()
                        } else if !newValue && isNotificationsGranted {
                            openNotificationSettings()
                        }
                    }
                ))
                .labelsHidden()
                .toggleStyle(.switch)
                .fixedSize()
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(width: 480, height: 70)
            .background(
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .fill(Color.white.opacity(0.6))
            )
            .offset(x: 105, y: 520 + cardsOffset)
            .opacity(cardsOpacity)


            Button(action: {
                print("Finish Setup tapped")
                PairingManager.shared.completeSetup()
            }) {
                Text("Finish Setup")
                    .font(.custom("SF Pro", size: 17))
                    .fontWeight(.semibold)
                    .foregroundColor(.black)
            }
            .frame(width: 132, height: 42)
            .background(RoundedRectangle(cornerRadius: 21).fill(Color.white.opacity(0.8)))
            .buttonStyle(.plain)
            .offset(x: 270, y: 615)
            .scaleEffect(buttonScale)
            .opacity(buttonOpacity)
        }
        .frame(width: 590, height: 590)
        .ignoresSafeArea()
        .onAppear {
            startAnimations()
            checkSystemPermissions()
            startPolling()
        }
        .onDisappear {
            permissionTimer?.invalidate()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            checkSystemPermissions()
        }
        .enableInjection()
    }


    // MARK: - Animations & Permissions

    /// Staggers spring animations for title, subtitle, permission cards, and the finish button.
    private func startAnimations() {

        withAnimation(.spring(response: 0.6, dampingFraction: 0.8)) {
            titleOpacity = 1
            titleOffset = 0
        }

        withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
            subtitleOpacity = 1
            subtitleOffset = 0
        }

        withAnimation(.spring(response: 0.7, dampingFraction: 0.8).delay(0.2)) {
            cardsOpacity = 1
            cardsOffset = 0
        }

        withAnimation(.spring(response: 0.5, dampingFraction: 0.7).delay(0.4)) {
            buttonOpacity = 1
            buttonScale = 1.0
        }


        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { withAnimation { showAccessibilityIcon = true } }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { withAnimation { showNetworkIcon = true } }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { withAnimation { showNotificationIcon = true } }
    }


    /// Reads AXIsProcessTrusted and UNNotificationSettings to refresh toggle states.
    private func checkSystemPermissions() {
        let axGranted = AXIsProcessTrusted()
        if isAccessibilityGranted != axGranted {
            accessibilityIntent = false
            withAnimation { isAccessibilityGranted = axGranted }
        }

        UNUserNotificationCenter.current().getNotificationSettings { settings in
            DispatchQueue.main.async {
                let noteGranted = (settings.authorizationStatus == .authorized)
                if self.isNotificationsGranted != noteGranted {
                    self.notificationIntent = false
                    withAnimation { self.isNotificationsGranted = noteGranted }
                }
            }
        }
    }


    /// Starts a 1-second repeating timer so permission toggles update in real time.
    private func startPolling() {
        permissionTimer?.invalidate()
        permissionTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { _ in
            checkSystemPermissions()
        }
    }


    /// Triggers the AX permission dialog then deep-links into System Settings > Privacy > Accessibility.
    private func requestAccessibilityPermission() {
        if isAccessibilityGranted {
            // Already granted — just open settings to show status
            openSystemSettings()
        } else {
            // Call AXIsProcessTrustedWithOptions with the prompt flag.
            // This registers the app in the TCC database so it appears in the
            // Accessibility list, AND shows the system alert on first run.
            let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
            let alreadyInTCC = AXIsProcessTrustedWithOptions(options)

            if alreadyInTCC {
                // App is trusted — polling will pick this up
                return
            }

            // Give macOS ~1.5 s to register the app in TCC before opening
            // Settings. If we open too fast the app isn't in the list yet.
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                self.openSystemSettings()
            }
        }
    }


    /// Shows the system notification permission dialog on first request; opens Settings if already denied.
    private func requestNotificationPermission() {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            DispatchQueue.main.async {
                if settings.authorizationStatus == .notDetermined {
                    // First time — show the system permission dialog
                    UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in
                        DispatchQueue.main.async {
                            withAnimation { isNotificationsGranted = granted }
                            if !granted { notificationIntent = false }
                        }
                    }
                } else {
                    // Already denied or restricted — open Settings so user can enable manually
                    openNotificationSettings()
                    notificationIntent = false
                }
            }
        }
    }


    /// Deep-links into System Settings > Privacy > Accessibility.
    private func openSystemSettings() {
        let urlString = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
        if let url = URL(string: urlString) {
            NSWorkspace.shared.open(url)
        }
    }


    /// Deep-links into System Settings > Notifications.
    private func openNotificationSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.notifications") {
            NSWorkspace.shared.open(url)
        }
    }
}

#Preview {
    FinalScreen()
        .frame(width: 590, height: 590)
}

