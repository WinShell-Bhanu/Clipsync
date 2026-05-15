


// ContentView.swift
// Root routing view. Directs to LandingScreen (unpaired), ConnectedScreen (paired +
// setup incomplete), or HomeScreen (fully set up). Shows a SplashScreen overlay for
// 4 seconds on first launch while pairing has not yet occurred.

import SwiftUI

// MARK: - ContentView

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

