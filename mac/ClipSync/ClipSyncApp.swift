//
// ClipSyncApp.swift
// ClipSync
//
import SwiftUI
import FirebaseCore
import AppKit
import Combine
import IOKit.pwr_mgt

@main
struct ClipSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @ObservedObject private var pairingManager = PairingManager.shared
    
    init() {
        // Initialize default user preferences

        UserDefaults.standard.register(defaults: [
            "syncToMac": true,
            "syncFromMac": true
        ])
        
        // --- Region Auto-Detection (REMOVED) ---
        // We now rely on Manual Selection in QRGenScreen for the initial setup.
        // This prevents the app from overriding the user's choice with a potentially wrong auto-detect.

        
        // --- Firebase & Sync Initialization ---
        _ = FirebaseManager.shared
        PairingManager.shared.restorePairing()
        
        if PairingManager.shared.isPaired {
             ClipboardManager.shared.startMonitoring()
             ClipboardManager.shared.listenForAndroidClipboard()
        }
    }
    
    var body: some Scene {
        WindowGroup(id: "main") {
            ContentView()
                .background(WindowConfigurator { window in
                    window.identifier = NSUserInterfaceItemIdentifier("mainWindow")
                    window.titleVisibility = .hidden
                    window.titlebarAppearsTransparent = true
                    window.styleMask.insert(.fullSizeContentView)
                    window.isOpaque = false
                    window.backgroundColor = .clear
                    window.toolbar?.showsBaselineSeparator = false
                    window.isMovableByWindowBackground = true
                })
        }
        .windowStyle(.hiddenTitleBar)
        .handlesExternalEvents(matching: Set(arrayLiteral: "main"))
        .windowToolbarStyle(.unified)
        .windowResizability(.contentSize)
        .defaultSize(width: 590, height: 590)
    }
}

// MARK: - Window Configurator
private struct WindowConfigurator: NSViewRepresentable {
    let configure: (NSWindow) -> Void
    
    init(_ configure: @escaping (NSWindow) -> Void) {
        self.configure = configure
    }
    
    func makeNSView(context: Context) -> NSView {
        let view = NSView(frame: .zero)
        DispatchQueue.main.async { [weak view] in
            if let win = view?.window { configure(win) }
        }
        return view
    }
    
    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async { [weak nsView] in
            if let win = nsView?.window { configure(win) }
        }
    }
}

// MARK: - Hot Reloading Support (Debug Only)
#if canImport(HotSwiftUI)
@_exported import HotSwiftUI
#elseif canImport(Inject)
@_exported import Inject
#else
#if DEBUG
import Combine

public class InjectionObserver: ObservableObject {
    public static let shared = InjectionObserver()
    @Published var injectionNumber = 0
    var cancellable: AnyCancellable? = nil
    let publisher = PassthroughSubject<Void, Never>()
    init() {
        cancellable = NotificationCenter.default.publisher(for:
            Notification.Name("INJECTION_BUNDLE_NOTIFICATION"))
            .sink { [weak self] change in
            self?.injectionNumber += 1
            self?.publisher.send()
        }
    }
}

extension SwiftUI.View {
    public func eraseToAnyView() -> some SwiftUI.View {
        return AnyView(self)
    }
    public func enableInjection() -> some SwiftUI.View {
        return eraseToAnyView()
    }
    public func onInjection(bumpState: @escaping () -> ()) -> some SwiftUI.View {
        return self
            .onReceive(InjectionObserver.shared.publisher, perform: bumpState)
            .eraseToAnyView()
    }
}

@available(iOS 13.0, macOS 10.15, tvOS 13.0, watchOS 6.0, *)
@propertyWrapper
public struct ObserveInjection: DynamicProperty {
    @ObservedObject private var iO = InjectionObserver.shared
    public init() {}
    public private(set) var wrappedValue: Int {
        get {0} set {}
    }
}
#else
extension SwiftUI.View {
    @inline(__always)
    public func eraseToAnyView() -> some SwiftUI.View { return self }
    @inline(__always)
    public func enableInjection() -> some SwiftUI.View { return self }
    @inline(__always)
    public func onInjection(bumpState: @escaping () -> ()) -> some SwiftUI.View {
        return self
    }
}

@available(iOS 13.0, macOS 10.15, tvOS 13.0, watchOS 6.0, *)
@propertyWrapper
public struct ObserveInjection {
    public init() {}
    public private(set) var wrappedValue: Int {
        get {0} set {}
    }
}
#endif
#endif

// MARK: - App Delegate (SINGLE UNIFIED VERSION)
class AppDelegate: NSObject, NSApplicationDelegate {
    var statusItem: NSStatusItem?
    var popover: NSPopover?
    var cancellables = Set<AnyCancellable>()
    var assertionID: IOPMAssertionID = 0
    
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Hide from Dock (Menu Bar App Mode)
        // REMOVED: Default to regular policy initially, updateDockPolicy will handle it.
        
        // Setup Popover
        let pop = NSPopover()
        pop.contentSize = NSSize(width: 280, height: 400)
        pop.behavior = .transient
        pop.contentViewController = NSHostingController(rootView: MenuBarView())
        self.popover = pop
        
        // Setup OTP Listener Delegate
        OTPNotificationManager.shared.delegate = self
        
        // Observe Pairing State for Dock, Menu Bar & OTP Listener
        PairingManager.shared.$isPaired
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paired in
                self?.updateMenuBarState(show: paired) // Menu Bar Icon
                self?.updateDockPolicy()               // Dock Icon
                
                // Start/Stop OTP Listener based on pairing state
                if paired {
                    OTPNotificationManager.shared.startListening()
                } else {
                    OTPNotificationManager.shared.stopListening()
                }
            }
            .store(in: &cancellables)
    }
    
    func applicationWillTerminate(_ notification: Notification) {
        OTPNotificationManager.shared.stopListening()
    }
    
    // Window Observers are NO LONGER needed for Dock Policy
    // Removing them to prevent interference

    func updateDockPolicy() {
        // New User Requirement:
        // 1. Setup/Unpaired -> Show Dock Icon (.regular)
        // 2. Paired -> Hide Dock Icon (.accessory) - EVEN if Settings window is open
        
        if PairingManager.shared.isPaired {
             // Paired Mode: Ghost in the machine (Menu Bar Only)
             if NSApp.activationPolicy() != .accessory {
                 NSApp.setActivationPolicy(.accessory)
                 print("Dock Policy: ACCESSORY (Paired)")
             }
        } else {
            // Setup Mode: Standard App behavior
            if NSApp.activationPolicy() != .regular {
                NSApp.setActivationPolicy(.regular)
                print("Dock Policy: REGULAR (Unpaired)")
            }
            
            // Ensure window is reachable in Setup Mode
            DispatchQueue.main.async {
                NSApp.activate(ignoringOtherApps: true)
            }
        }
    }
    
    func updateMenuBarState(show: Bool) {
        if show {
            if statusItem == nil {
                let newItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
                if let button = newItem.button {
                    button.image = NSImage(systemSymbolName: "doc.on.clipboard", accessibilityDescription: "ClipSync")
                    button.action = #selector(togglePopover(_:))
                }
                statusItem = newItem
            }
        } else {
            if let item = statusItem {
                NSStatusBar.system.removeStatusItem(item)
                statusItem = nil
            }
        }
    }
    
    @objc func togglePopover(_ sender: AnyObject?) {
        // Feature: If an OTP arrived recently (< 1 min), clicking the icon re-shows the bubbles
        // instead of opening the standard menu.
        if OTPNotificationManager.shared.hasRecentOTP {
            OTPNotificationManager.shared.reshowLastBubble()
            return
        }
        
        guard let button = statusItem?.button, let popover = popover else { return }
        
        if popover.isShown {
            popover.performClose(sender)
        } else {
            popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
            NSApp.activate(ignoringOtherApps: true)
        }
    }
    
    func preventAppSleep() {
        let reason = "ClipSync needs to monitor clipboard" as CFString
        let success = IOPMAssertionCreateWithName(
            kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
            IOPMAssertionLevel(kIOPMAssertionLevelOn),
            reason,
            &assertionID
        )
        
        if success == kIOReturnSuccess {
            // Sleep prevention successful
        } else {
            // Failed to prevent sleep
        }
    }
    
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }
    
    deinit {
        NotificationCenter.default.removeObserver(self)
        if assertionID != 0 {
            IOPMAssertionRelease(assertionID)
        }
    }
}
