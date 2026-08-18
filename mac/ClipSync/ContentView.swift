
// ContentView.swift
// Root routing view. Directs to LandingScreen (unpaired/onboarding) or NewHomeScreen (fully set up).

import SwiftUI

// MARK: - ContentView

struct ContentView: View {
    @ObservedObject private var pairingManager = PairingManager.shared
    @State private var showSplash = !PairingManager.shared.isPaired

    #if DEBUG
    #endif

    var body: some View {
        ZStack {
            // Only switch to the home dashboard once BOTH pairing AND onboarding
            // setup are complete. While isPaired is true but isSetupComplete is false
            // the user is mid-onboarding (WiFiConnect → ConnectedScreen → FinalScreen)
            // and must not be yanked to NewHomeScreen prematurely.
            if pairingManager.isPaired && pairingManager.isSetupComplete {
                NavigationStack {
                    NewHomeScreen()
                }
            } else {
                LandingScreen(isBackgroundPaused: showSplash)
            }

            if showSplash {
                SplashScreen()
                    .transition(.opacity)
                    .zIndex(1)
                    .onAppear {
                        DispatchQueue.main.asyncAfter(deadline: .now() + 4.0) {
                            withAnimation(.spring(response: 0.4, dampingFraction: 1.0)) {
                                showSplash = false
                            }
                        }
                    }
            }
        }
        .ignoresSafeArea()
    }
}


#Preview {
    ContentView()
}
