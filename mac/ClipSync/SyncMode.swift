// SyncMode.swift
// Screen for selecting the Sync Mode (Hybrid vs Local).
// Implements Figma design with interactive glassmorphic cards.
// onModeSelected is called with "hybrid" or "local" after the user confirms.

import SwiftUI

// MARK: - SyncMode Screen

struct SyncMode: View {
    var onModeSelected: (String) -> Void = { _ in }

    @State private var contentOpacity: Double = 0
    @State private var contentOffset: CGFloat = 20
    @State private var pendingMode: String? = nil
    @State private var showConfirmAlert: Bool = false
    @State private var navigateToBLE: Bool = false

    var body: some View {
        ZStack {
            // Background from Figma (MeshBackground)
            MeshBackground(shouldAnimate: false)
                .ignoresSafeArea()

            VStack(spacing: 0) {
                // Top bar with refresh icon
                HStack {
                    Spacer()
                    Button(action: {
                        // Refresh or Auto-Renew action
                    }) {
                        Image(systemName: "arrow.triangle.2.circlepath")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                    }
                    .buttonStyle(.plain)
                    .padding(.trailing, 30)
                    .padding(.top, 60)
                }

                // Title
                Text("Select your\nSync Mode")
                    .font(.custom("SF Pro Display", size: 52).bold())
                    .kerning(-1.56)
                    .foregroundColor(.white)
                    .shadow(color: Color.black.opacity(0.2), radius: 21.4, x: 0, y: 4)
                    .shadow(color: Color.white.opacity(0.22), radius: 23.2, x: 0, y: 0)
                    .multilineTextAlignment(.center)
                    .lineSpacing(-2)
                    .padding(.top, 20)

                // Cards
                HStack(spacing: 24) {
                    SyncModeCard(
                        iconName: "globe",
                        title: "Hybrid Sync",
                        subtitle: "Sync locally when nearby. Sync via Cloud when far away for seamless productivity",
                        row1Features: ["Anywhere", "Auto-Switch"],
                        row2Features: ["Encrypted"],
                        action: {
                            pendingMode = "hybrid"
                            showConfirmAlert = true
                        }
                    )

                    SyncModeCard(
                        iconName: "wifi.router",
                        title: "Local Sync",
                        subtitle: "Sync only with nearby devices over BLE and Wi-Fi. No cloud, no internet required",
                        row1Features: ["Nearby Only", "Fully Offline"],
                        row2Features: ["100% Private"],
                        action: {
                            pendingMode = "local"
                            showConfirmAlert = true
                        }
                    )
                }
                .padding(.horizontal, 20)
                .padding(.top, 35)

                Spacer()
            }
            .opacity(contentOpacity)
            .offset(y: contentOffset)
        }
        .frame(width: 590, height: 590)
        .onAppear {
            // Entrance animation
            withAnimation(.spring(response: 0.6, dampingFraction: 0.8)) {
                contentOpacity = 1
                contentOffset = 0
            }
        }
        .navigationDestination(isPresented: $navigateToBLE) {
            BLEDiscover()
        }
        .alert(
            pendingMode == "hybrid" ? "Use Hybrid Sync?" : "Use Local Sync?",
            isPresented: $showConfirmAlert,
            presenting: pendingMode
        ) { mode in
            Button("Confirm") {
                UserDefaults.standard.set(mode, forKey: "sync_mode")
                onModeSelected(mode)
                navigateToBLE = true
            }
            Button("Change Mode", role: .cancel) {
                pendingMode = nil
            }
        } message: { mode in
            Text(mode == "hybrid"
                 ? "Sync locally when nearby. Switch to cloud sync automatically when you're far away."
                 : "Sync only over local Wi-Fi & Bluetooth. No cloud, fully private."
            )
        }
    }
}

// MARK: - SyncModeCard Component


struct SyncModeCard: View {
    let iconName: String
    let title: String
    let subtitle: String
    let row1Features: [String]
    let row2Features: [String]
    let action: () -> Void

    @State private var isHovered = false

    var body: some View {
        Button(action: action) {
            ZStack {
                // Glassmorphism Base
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.white.opacity(0.4))
                    .shadow(color: Color.black.opacity(0.1), radius: 15, x: 0, y: 10)
                    .overlay(
                        RoundedRectangle(cornerRadius: 24, style: .continuous)
                            .stroke(Color.white.opacity(0.3), lineWidth: 1)
                    )

                VStack(spacing: 0) {
                    // SVG Icon
                    Image(iconName)
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(height: 80)
                        .padding(.top, 20)

                    // Card Title
                    Text(title)
                        .font(.system(size: 33, weight: .medium))
                        .foregroundColor(.black)
                        .shadow(color: Color.black.opacity(0.27), radius: 10, x: 0, y: 4)
                        .padding(.top, 12)

                    // Card Subtitle
                    Text(subtitle)
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(Color(red: 0.27, green: 0.274, blue: 0.329)) // Figma #454654
                        .multilineTextAlignment(.center)
                        .lineSpacing(2)
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                        .frame(height: 75, alignment: .top)

                    // Separator Line
                    Rectangle()
                        .fill(LinearGradient(
                            colors: [.clear, .black.opacity(0.2), .clear],
                            startPoint: .leading,
                            endPoint: .trailing
                        ))
                        .frame(width: 200, height: 1)
                        .padding(.top, 5)

                    // Feature Capsules
                    VStack(spacing: 12) {
                        HStack(spacing: 10) {
                            ForEach(row1Features, id: \.self) { feature in
                                FeatureCapsule(text: feature)
                            }
                        }
                        HStack(spacing: 10) {
                            ForEach(row2Features, id: \.self) { feature in
                                FeatureCapsule(text: feature)
                            }
                        }
                    }
                    .padding(.top, 15)
                    .padding(.bottom, 20)
                }
            }
            .frame(width: 263, height: 362)
            // Interactive hover effect
            .scaleEffect(isHovered ? 1.03 : 1.0)
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: isHovered)
        }
        .buttonStyle(.plain)
        .onHover { hovering in
            isHovered = hovering
        }
    }
}

// MARK: - FeatureCapsule Component

struct FeatureCapsule: View {
    let text: String

    var body: some View {
        HStack(spacing: 4) {
            Text(text)
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.black)
                .lineLimit(1)
                .layoutPriority(1)

            // Check icon SVG
            Image("check_circle")
                .renderingMode(.original)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 14, height: 14)
                .layoutPriority(2)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule()
                .fill(Color.white.opacity(0.45))
                .shadow(color: Color.black.opacity(0.06), radius: 10, x: 0, y: 0)
        )
        .overlay(
            Capsule()
                .stroke(Color.white.opacity(0.37), lineWidth: 1)
        )
    }
}

#Preview {
    SyncMode()
}
