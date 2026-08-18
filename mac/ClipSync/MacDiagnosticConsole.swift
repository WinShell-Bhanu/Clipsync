import SwiftUI
import CoreWLAN
import CoreBluetooth

struct MacDiagnosticConsole: View {
    @Environment(\.dismiss) var dismiss
    
    @State private var logLines: [String] = []
    @State private var isChecking = true
    
    let notificationCenter = NotificationCenter.default
    
    var body: some View {
        VStack(spacing: 0) {
            // Header
            HStack {
                Text("● ● ●")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color.gray.opacity(0.8))
                Spacer()
                Text("Diagnostic Console")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(.white)
                Spacer()
                Button(action: { dismiss() }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(.gray)
                }
                .buttonStyle(PlainButtonStyle())
            }
            .padding()
            .background(Color.black)
            
            // Console body
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(logLines.indices, id: \.self) { i in
                            Text(logLines[i])
                                .font(.system(size: 13, design: .monospaced))
                                .foregroundColor(logLines[i].contains("✓") ? .green : (logLines[i].contains("✗") ? .red : (logLines[i].contains("!") ? .yellow : .green.opacity(0.8))))
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding()
                }
                .onChange(of: logLines.count) {
                    if logLines.count > 0 {
                        proxy.scrollTo(logLines.count - 1, anchor: .bottom)
                    }
                }
            }
            .background(Color(white: 0.12))
            
            // Footer
            if !isChecking {
                HStack {
                    Spacer()
                    Button("Close") {
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.blue)
                    .padding()
                }
                .background(Color(white: 0.12))
            }
        }
        .frame(width: 500, height: 400)
        .cornerRadius(12)
        .onAppear {
            runDiagnostics()
        }
        .onReceive(notificationCenter.publisher(for: NSNotification.Name("DiagnosticPingReceived"))) { _ in
            logLines.append("  ✓ Received BLE Ping from Android")
            logLines.append("  ✓ Sent BLE ACK to Android")
        }
        .onReceive(notificationCenter.publisher(for: NSNotification.Name("DiagnosticTCPPingReceived"))) { _ in
            logLines.append("  ✓ Received TCP Ping from Android on port \(ClipSyncServer.shared.dynamicPort)")
        }
    }
    
    private func runDiagnostics() {
        logLines.append("> Starting macOS Diagnostics...")
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            logLines.append("> Checking Bluetooth adapter...")
            
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                if WakeupReceiver.shared.isReady {
                    logLines.append("  ✓ Enabled and advertising")
                } else {
                    logLines.append("  ✗ Bluetooth is off, denied, or not advertising")
                }
                
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    logLines.append("> Checking Wi-Fi interface...")
                    
                    let wifiClient = CWWiFiClient.shared()
                    let interface = wifiClient.interface()
                    
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                        if let interface = interface, interface.powerOn() {
                            let ssid = interface.ssid() ?? "Unknown Network"
                            logLines.append("  ✓ Connected to \"\(ssid)\"")
                        } else {
                            logLines.append("  ✗ Wi-Fi adapter is powered off or missing")
                        }
                        
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                            logLines.append("> Checking TCP Server...")
                            
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                                if ClipSyncServer.shared.isListening {
                                    logLines.append("  ✓ Listening on port \(ClipSyncServer.shared.dynamicPort)")
                                } else {
                                    logLines.append("  ✗ Server not listening. Attempting restart...")
                                    ClipSyncServer.shared.start()
                                    if ClipSyncServer.shared.isListening {
                                        logLines.append("  ✓ Server restarted")
                                    } else {
                                        logLines.append("  ✗ Server failed to start")
                                    }
                                }
                                
                                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                                    logLines.append("> Waiting for active pings from Android...")
                                    logLines.append("  ! Open the ClipSync app on your Android and run Diagnostics")
                                    
                                    // Keep checking state alive so user knows it's waiting
                                    // We won't set isChecking = false here, so the Close button stays hidden?
                                    // Actually, let's show the close button so they aren't trapped.
                                    DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                                        isChecking = false
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
