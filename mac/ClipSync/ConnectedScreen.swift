import SwiftUI
import AppKit
import Lottie

struct ConnectedScreen: View {
    @StateObject private var pairingManagerr = PairingManager.shared
    @State private var navigateToFinal = false
    

    
    // --- Animation States ---
    @State private var titleOpacity: Double = 0
    @State private var titleOffset: CGFloat = -30
    @State private var subtitleOpacity: Double = 0
    @State private var subtitleOffset: CGFloat = -20
    @State private var lottieOpacity: Double = 0
    @State private var lottieScale: CGFloat = 0.9
    @State private var buttonOpacity: Double = 0
    @State private var buttonOffset: CGFloat = 20
    
    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {
            // --- Background ---
            MeshBackground()
                .ignoresSafeArea()
            
            // Title (Top)
            Text("You're Connected")
                .font(.custom("SF Pro Display", size: 48))
                .fontWeight(.bold)
                .kerning(-1.2)
                .foregroundColor(.white)
                .multilineTextAlignment(.center)
                .offset(y: -210 + titleOffset)
                .opacity(titleOpacity)
            
            // Subtitle (Below Title)
            Text("Your Phone is now linked\nwith \(DeviceManager.shared.getFriendlyMacName())")
                .font(.custom("SF Pro", size: 22))
                .fontWeight(.medium)
                .lineSpacing(4)
                .multilineTextAlignment(.center)
                .foregroundColor(Color(red: 0.125, green: 0.263, blue: 0.600))
                .offset(y: -145 + subtitleOffset)
                .opacity(subtitleOpacity)
            
            // Animation (Center)
            ConnectLottieView(filename: "DeviceSync")
                .frame(width: 625, height: 450)
                .offset(y: 70)
                .opacity(lottieOpacity)
                .scaleEffect(lottieScale)
            
            // Continue Button (Bottom)
            Button(action: {
                navigateToFinal = true
            }) {
                Text("Continue")
                    .font(.custom("SF Pro", size: 17))
                    .fontWeight(.semibold)
                    .foregroundColor(.black)
            }
            .frame(width: 132, height: 42)
            .background(
                RoundedRectangle(cornerRadius: 21, style: .continuous)
                    .fill(Color.white.opacity(0.9))
                    .shadow(color: Color.black.opacity(0.1), radius: 4, x: 0, y: 2)
            )
            .buttonStyle(.plain)
            .offset(y: 245 + buttonOffset)
            .opacity(buttonOpacity)
        }
        .frame(width: 590, height: 590)
        .ignoresSafeArea()
        .onAppear {
            ClipboardManager.shared.startMonitoring()
            ClipboardManager.shared.listenForAndroidClipboard()
            playEntranceAnimations()
        }
        .navigationDestination(isPresented: $navigateToFinal) {
            FinalScreen()
        }
        .enableInjection()
    }
    
    // MARK: - Animations
    private func playEntranceAnimations() {
        // Title
        withAnimation(.spring(response: 0.6, dampingFraction: 0.8)) {
            titleOpacity = 1
            titleOffset = 0
        }
        
        // Subtitle
        withAnimation(.spring(response: 0.6, dampingFraction: 0.8).delay(0.1)) {
            subtitleOpacity = 1
            subtitleOffset = 0
        }
        
        // Lottie
        withAnimation(.spring(response: 0.7, dampingFraction: 0.7).delay(0.2)) {
            lottieOpacity = 1
            lottieScale = 1.0
        }
        
        // Button
        withAnimation(.spring(response: 0.5, dampingFraction: 0.7).delay(0.3)) {
            buttonOpacity = 1
            buttonOffset = 0
        }
    }
}

// RENAMED STRUCT to avoid conflict with Lottie's internal "LottieView"
struct ConnectLottieView: NSViewRepresentable {
    var filename: String
    
    func makeNSView(context: Context) -> NSView {
        let containerView = NSView(frame: .zero)
        containerView.wantsLayer = true
        containerView.layer?.masksToBounds = true
        
        // Create animation view
        let animationView = LottieAnimationView(name: filename)
        animationView.contentMode = .scaleAspectFit
        animationView.loopMode = .playOnce
        animationView.animationSpeed = 0.75
        animationView.backgroundBehavior = .pauseAndRestore
        
        // Critical: Use autoresizing mask for proper scaling
        animationView.autoresizingMask = [.width, .height]
        animationView.translatesAutoresizingMaskIntoConstraints = true
        
        containerView.addSubview(animationView)
        
        // Start playing
        animationView.play()
        
        return containerView
    }
    
    func updateNSView(_ nsView: NSView, context: Context) {
        if let animationView = nsView.subviews.first as? LottieAnimationView {
            animationView.frame = nsView.bounds
        }
    }
}

#Preview {
    ConnectedScreen()
        .frame(width: 590, height: 590)
}

