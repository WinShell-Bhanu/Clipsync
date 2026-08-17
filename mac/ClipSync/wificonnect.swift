// wificonnect.swift
// Mac "Setting up fast local sync" screen — mirrors the Android LocalNetworkScreen.
// Shows three live steps driven by ClipSyncServer.isListening and transfer state.

import SwiftUI

struct WiFiConnect: View {

    @ObservedObject private var server  = ClipSyncServer.shared
    @ObservedObject private var wakeup  = WakeupReceiver.shared

    // Derived step states
    private var step1Done:   Bool { server.isListening }
    private var step2Done:   Bool { wakeup.lastPing != nil }
    private var step3Active: Bool { server.transferProgress > 0 && server.transferProgress < 1 }
    private var step3Done:   Bool { server.transferProgress >= 1 }
    @State private var navigateToConnected = false
    @State private var hasFailed = false

    var body: some View {
        ZStack(alignment: .topLeading) {

            // 1. Background
            MeshBackground(shouldAnimate: false)
                .frame(width: 861.5, height: 920.15)
                .position(x: 338.9, y: 313.4)
                .ignoresSafeArea()

            // 2. Router icon
            Group {
                if NSImage(named: "Group") != nil {
                    Image("Group")
                        .renderingMode(.original)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                } else {
                    Image(systemName: "wifi.router.fill")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .foregroundColor(.white)
                }
            }
            .frame(width: 171, height: 113)
            .position(x: 290.5, y: 113.5)

            // 3. Title
            Text("Setting up fast local sync")
                .font(.system(size: 36, weight: .bold))
                .tracking(-1.08)
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .shadow(color: Color.black.opacity(0.2), radius: 10.7, x: 0, y: 4)
                .shadow(color: Color.white.opacity(0.22), radius: 11.6, x: 0, y: 0)
                .frame(width: 399, height: 36)
                .position(x: 295.0, y: 199.0)

            // 4. Progress card
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.white.opacity(0.4))
                .frame(width: 362, height: 288)
                .position(x: 295.0, y: 389.0)

            // ── Step 1: TCP server listening ─────────────────────────────────
            StepRow(
                done:     step1Done,
                active:   !step1Done,
                label:    "TCP server listening",
                sublabel: step1Done ? "Port \(server.dynamicPort) ready" : "Starting server…"
            )
            .position(x: 285.0, y: 282.0)

            // ── Step 2: Android wakeup ping received ─────────────────────────
            StepRow(
                done:     step2Done,
                active:   step1Done && !step2Done,
                label:    "Android connected",
                sublabel: wakeup.lastPing.map { "From \($0.ip)" }
                            ?? "Waiting for wakeup ping…"
            )
            .position(x: 285.0, y: 364.0)

            // ── Step 3: Transfer in progress / done ──────────────────────────
            StepRow(
                done:     step3Done || navigateToConnected,
                active:   step3Active && !navigateToConnected,
                label:    "Secure Handshake",
                sublabel: hasFailed 
                    ? "Couldn't reach Mac — check Wi-Fi" 
                    : (navigateToConnected ? "Handshake complete ✓" : "Waiting for Android...")
            )
            .position(x: 285.0, y: 449.0)
        }
        .frame(width: 590, height: 590)
        .onAppear {
            ClipSyncServer.shared.start()
            WakeupReceiver.shared.start()
        }
        .onChange(of: step2Done) { oldValue, newValue in
            // No longer automatically advancing just because we got a ping. Must wait for TCP connection.
        }
        .onChange(of: server.hasActiveClient) { oldValue, newValue in
            if newValue == true {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    navigateToConnected = true
                }
            }
        }

        .navigationDestination(isPresented: $navigateToConnected) {
            ConnectedScreen()
        }
    }
}

// MARK: - Step row component

private struct StepRow: View {
    let done:     Bool
    let active:   Bool
    let label:    String
    let sublabel: String

    @State private var rotation: Double = 0

    var body: some View {
        HStack(alignment: .center, spacing: 14) {
            // Indicator
            ZStack {
                if done {
                    Image(systemName: "checkmark.circle.fill")
                        .resizable()
                        .foregroundColor(.green)
                        .frame(width: 28, height: 28)
                } else if active {
                    Image(systemName: "arrow.2.circlepath")
                        .resizable()
                        .foregroundColor(.white.opacity(0.9))
                        .frame(width: 20, height: 20)
                        .rotationEffect(.degrees(rotation))
                        .onAppear {
                            withAnimation(.linear(duration: 1.0).repeatForever(autoreverses: false)) {
                                rotation = 360
                            }
                        }
                } else {
                    Circle()
                        .strokeBorder(Color.white.opacity(0.4), lineWidth: 2)
                        .frame(width: 28, height: 28)
                }
            }
            .frame(width: 32)

            // Labels
            VStack(alignment: .leading, spacing: 2) {
                Text(label)
                    .font(.system(size: 15, weight: .bold))
                    .tracking(-0.45)
                    .foregroundColor(done || active ? .black : Color.black.opacity(0.5))

                Text(sublabel)
                    .font(.system(size: 12, weight: .medium))
                    .tracking(-0.36)
                    .foregroundColor(Color.black.opacity(0.35))
            }
        }
        .frame(width: 300, alignment: .leading)
        .opacity(done || active ? 1.0 : 0.5)
    }
}

#Preview {
    WiFiConnect()
}
