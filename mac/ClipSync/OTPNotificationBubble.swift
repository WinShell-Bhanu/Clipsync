// OTPNotificationBubble.swift
// Floating semi-transparent bubble that appears below the menu bar icon when an OTP arrives.
// OTP digits are masked until the user hovers over them. Shows a Lottie tick animation
// and a shimmer effect. Auto-dismisses after ~5 seconds with a fade-out animation.

import SwiftUI
import Lottie
import Shimmer

// MARK: - OTPNotificationBubble

struct OTPNotificationBubble: View {
    let otpCode: String
    @State private var opacity: Double = 0
    @State private var scale: CGFloat = 0.8
    @State private var offset: CGFloat = 10
    @State private var isHovering: Bool = false
    @State private var isShimmerActive: Bool = true

    private let animationId = UUID()

    var body: some View {
        VStack(spacing: 8) {
            TickLottieView()
                .frame(width: 50, height: 50)
                .scaleEffect(1.2)
                .id(animationId)

            Text(isHovering ? otpCode : String(repeating: "*", count: otpCode.count))
                .font(.system(size: 20, weight: .bold, design: .rounded))
                .foregroundColor(.primary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .strokeBorder(Color.blue.opacity(0.2), lineWidth: 1)
                )
                .onHover { hovering in
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isHovering = hovering
                    }
                }

            Text("OTP Copied Successfully")
                .font(.system(size: 12, weight: .medium))
                .foregroundColor(.white.opacity(0.9))
                .shimmering(
                    active: isShimmerActive,
                    animation: .linear(duration: 3.5).delay(0).repeatForever(autoreverses: false)
                )
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(.white.opacity(0.15), lineWidth: 0.5)
        )
        .shadow(color: Color.black.opacity(0.15), radius: 20, x: 0, y: 10)
        .opacity(opacity)
        .scaleEffect(scale)
        .offset(y: offset)
        .onAppear {
            withAnimation(.interpolatingSpring(stiffness: 120, damping: 12)) {
                opacity = 1
                scale = 1
                offset = 0
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 4.5) {
                isShimmerActive = false
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) {
                withAnimation(.easeOut(duration: 0.3)) {
                    opacity = 0
                    scale = 0.9
                    offset = -10
                }
            }
        }
    }
}


// MARK: - OTPBubbleWindow

/// Borderless NSWindow that positions the OTP bubble below the status bar button.
/// Auto-closes via a Timer after 5.5 seconds.
class OTPBubbleWindow: NSWindow {
    private var autoCloseTimer: Timer?


    init(otpCode: String, statusItemButton: NSStatusBarButton) {
        let buttonFrame = statusItemButton.window?.convertToScreen(statusItemButton.frame) ?? .zero

        let bubbleWidth: CGFloat = 220
        let bubbleHeight: CGFloat = 140
        let xPosition = buttonFrame.midX - (bubbleWidth / 2)
        let yPosition = buttonFrame.minY - bubbleHeight - 8

        let contentRect = NSRect(
            x: xPosition,
            y: yPosition,
            width: bubbleWidth,
            height: bubbleHeight
        )

        super.init(
            contentRect: contentRect,
            styleMask: [.borderless],
            backing: .buffered,
            defer: false
        )

        self.isOpaque = false
        self.backgroundColor = .clear
        self.hasShadow = false
        self.level = .statusBar
        self.collectionBehavior = [.canJoinAllSpaces, .stationary]
        self.isMovable = false
        self.ignoresMouseEvents = false
        self.isReleasedWhenClosed = false

        let hostingView = NSHostingView(rootView: OTPNotificationBubble(otpCode: otpCode))
        hostingView.layer?.backgroundColor = NSColor.clear.cgColor
        self.contentView = hostingView

        self.autoCloseTimer = Timer.scheduledTimer(withTimeInterval: 5.5, repeats: false) { [weak self] _ in
            self?.cleanup()
        }
    }


    private func cleanup() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.autoCloseTimer?.invalidate()
            self.autoCloseTimer = nil
            self.contentView = nil
            self.orderOut(nil)
            self.close()
        }
    }

    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }


    deinit {
        autoCloseTimer?.invalidate()
        autoCloseTimer = nil
    }
}

#Preview {
    OTPNotificationBubble(otpCode: "123456")
        .frame(width: 220, height: 140)
        .padding()
        .background(Color.black)
}
