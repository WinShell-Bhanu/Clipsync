import SwiftUI
import CoreBluetooth
import Combine
import Lottie
import Shimmer

struct BLEDiscover: View {
    var onHandshakeComplete: (() -> Void)? = nil
    
    @State private var isAnimating = false
    @State private var navigateToQR = false
    // No longer owns its own peripheral manager — uses the single shared GATT server.
    @StateObject private var wakeupReceiver = WakeupReceiver.shared
    @Environment(\.presentationMode) var presentationMode
    
    #if DEBUG
    #endif

    var body: some View {
        ZStack(alignment: .topLeading) {
            
            // 1. Mesh Background
            MeshBackground(shouldAnimate: false)
                .frame(width: 861.5, height: 920.15)
                .position(x: 338.9, y: 313.4)
                .ignoresSafeArea()
            
            // 2. Center Animation Area
            ZStack {
                // Concentric rings
                Circle()
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
                    .frame(width: 222, height: 222)
                    .scaleEffect(isAnimating ? 1.05 : 0.95)
                    .opacity(isAnimating ? 0.3 : 1)
                    .animation(.easeInOut(duration: 2.0).repeatForever(autoreverses: true), value: isAnimating)
                
                Circle()
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
                    .frame(width: 175.4, height: 175.4)
                    .scaleEffect(isAnimating ? 1.1 : 0.9)
                    .opacity(isAnimating ? 0 : 1)
                    .animation(.easeOut(duration: 2.0).repeatForever(autoreverses: false).delay(0.5), value: isAnimating)
                
                Circle()
                    .stroke(Color.white.opacity(0.2), lineWidth: 1)
                    .frame(width: 126.1, height: 126.1)
                    .scaleEffect(isAnimating ? 1.2 : 0.8)
                    .opacity(isAnimating ? 0 : 1)
                    .animation(.easeOut(duration: 2.0).repeatForever(autoreverses: false), value: isAnimating)
                
                // Outer glow
                Circle()
                    .fill(Color.white.opacity(0.2))
                    .frame(width: 192, height: 192)
                    .blur(radius: 10)
                    .blendMode(.overlay)
                
                // Core shape
                ZStack {
                    Circle()
                        .fill(Color.white.opacity(0.1))
                        .frame(width: 87.7, height: 87.7)
                        .shadow(color: Color(red: 0.125, green: 0.263, blue: 0.6).opacity(0.15), radius: 16, x: 0, y: 8)
                        .overlay(
                            Circle()
                                .stroke(Color.white.opacity(0.3), lineWidth: 1)
                        )
                    
                    LottieView { try await DotLottieFile.named("Bluetooth") }
                        .playing(loopMode: .playOnce)
                        .resizable()
                        .frame(width: 64, height: 64)
                }
            }
            .position(x: 291.6, y: 159.0)
            // 4. Title
            Text("Mac is Discoverable")
                .font(.system(size: 40, weight: .bold))
                .tracking(-1.2)
                .foregroundColor(.white)
                .shadow(color: Color.black.opacity(0.2), radius: 10.7, x: 0, y: 4)
                .shadow(color: Color.white.opacity(0.22), radius: 11.6, x: 0, y: 0)
                .frame(width: 362, height: 48)
                .position(x: 295.0, y: 310.0)
            
            // 5. Subtitle
            Text("Launch ClipSync on your Android device and\nstart a scan to bridge the gap.")
                .font(.system(size: 15, weight: .bold))
                .tracking(-0.45)
                .foregroundColor(Color(red: 0.216, green: 0.216, blue: 0.231))
                .multilineTextAlignment(.center)
                .lineSpacing(2)
                .frame(width: 450, height: 50)
                .position(x: 294.0, y: 357.0)
            
            // 6. Bottom Card (Broadcasting as)
            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.white.opacity(0.4))
                    .frame(width: 269, height: 62)
                
                Group {
                    if let _ = NSImage(named: "macbook_badge") {
                        Image("macbook_badge")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                    } else {
                        Image(systemName: "laptopcomputer")
                            .font(.system(size: 24))
                            .foregroundColor(.black)
                    }
                }
                .frame(width: 60, height: 38)
                .position(x: 172.0 - 161.0 + 30.0, y: 422.0 - 410.0 + 19.0) // center within card
                
                Text("Broadcasting as")
                    .font(.system(size: 15, weight: .medium))
                    .tracking(-0.45)
                    .foregroundColor(Color(red: 0.0, green: 0.478, blue: 1.0))
                    .frame(width: 110, height: 20, alignment: .leading)
                    .position(x: 246.0 - 161.0 + 55.0, y: 422.0 - 410.0 + 10.0)
                
                Text(Host.current().localizedName ?? "Mac")
                    .font(.system(size: 16, weight: .medium))
                    .tracking(-0.48)
                    .foregroundColor(.black)
                    .shimmering(active: true, animation: .easeInOut(duration: 4.0).repeatForever(autoreverses: true), bandSize: 0.6)
                    .frame(width: 161, height: 20, alignment: .leading)
                    .position(x: 246.0 - 161.0 + 80.5, y: 441.0 - 410.0 + 10.0)
            }
            .frame(width: 269, height: 62)
            .position(x: 295.5, y: 441.0)
            
            // 7. Cancel Button
            Button(action: {
                wakeupReceiver.stop()
                presentationMode.wrappedValue.dismiss()
            }) {
                Text("CANCEL")
                    .font(.system(size: 16, weight: .bold))
                    .tracking(0.6)
                    .foregroundColor(Color(red: 0.725, green: 0.063, blue: 0.063))
                    .frame(width: 88, height: 32)
                    .background(
                        Capsule()
                            .fill(Color.white.opacity(0.45))
                            .shadow(color: Color.black.opacity(0.06), radius: 5, x: 0, y: 0)
                    )
                    .overlay(
                        Capsule()
                            .stroke(Color.white.opacity(0.37), lineWidth: 1)
                    )
            }
            .buttonStyle(.plain)
            .position(x: 295.0, y: 541.0)
            
        }
        .frame(width: 590, height: 590)
        .onAppear {
            isAnimating = true
            
            wakeupReceiver.onDeviceConnected = {
                UserDefaults.standard.removeObject(forKey: "ble_pairing_uuid")
                onHandshakeComplete?()
                DispatchQueue.main.async {
                    navigateToQR = true
                }
            }
            wakeupReceiver.start()
        }
        .navigationDestination(isPresented: $navigateToQR) {
            QRGenScreen()
        }
    }
}

#Preview {
    BLEDiscover()
}
