


import SwiftUI


// Purpose: UI component that renders state and user interactions.
// Responsibilities: Encapsulates content view behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct ContentView: View {
    @StateObject private var pairingManager = PairingManager.shared

    @State private var showSplash = !PairingManager.shared.isPaired

    #if DEBUG
    @ObserveInjection var forceRedraw
    #endif

    var body: some View {
        ZStack {

            if pairingManager.isPaired {
                if pairingManager.isSetupComplete {
                    NavigationStack {
                        HomeScreen()
                    }
                } else {
                    NavigationStack {
                        ConnectedScreen()
                    }
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
                            withAnimation(.easeOut(duration: 0.8)) {
                                showSplash = false
                            }
                        }
                    }
            }
        }
        .ignoresSafeArea()
        .enableInjection()
    }
}

#Preview {
    ContentView()
}

