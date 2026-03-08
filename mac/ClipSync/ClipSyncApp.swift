


// ClipSyncApp.swift
// App entry point. Registers defaults, boots Firebase, and starts clipboard sync
// if the Mac is already paired. Also owns the main SwiftUI window configuration.

import SwiftUI
import FirebaseCore
import FirebaseMessaging
import AppKit
import Combine
import IOKit.pwr_mgt
import UserNotifications

// MARK: - App Entry Point

@main
struct ClipSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @ObservedObject private var pairingManager = PairingManager.shared

    /// Registers default preferences, initialises Firebase, and resumes clipboard
    /// sync/listening if a valid pairing was previously saved.
    init() {


        UserDefaults.standard.register(defaults: [
            "syncToMac": true,
            "syncFromMac": true
        ])


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


// MARK: - Window Configuration

/// Applies NSWindow styling (transparent title bar, vibrancy, movable by background)
/// to the SwiftUI window on first layout via an invisible NSView bridge.
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

// MARK: - Hot-Reload (Debug Only)

#if canImport(HotSwiftUI)
@_exported import HotSwiftUI
#elseif canImport(Inject)
@_exported import Inject
#else
#if DEBUG
import Combine

/// Observes `INJECTION_BUNDLE_NOTIFICATION` so views can force-redraw on hot-reload.
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


// MARK: - AppDelegate

/// Wires up FCM messaging, push notifications, the menu bar status item, and
/// the OTP popover. Also manages the Dock icon visibility based on paired state.
class AppDelegate: NSObject, NSApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate, OTPNotificationDelegate {

    // MARK: - Properties

    var statusItem: NSStatusItem?
    var popover: NSPopover?
    var cancellables = Set<AnyCancellable>()
    var assertionID: IOPMAssertionID = 0

    // MARK: - Lifecycle

    /// Boots FCM, notification permissions, builds the popover, and starts
    /// observing pairing state to show/hide the menu bar icon.
    func applicationDidFinishLaunching(_ notification: Notification) {

        // Set FCM delegate
        Messaging.messaging().delegate = self
        
        UNUserNotificationCenter.current().delegate = self
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if granted {
                print("✅ Notification permission granted")
            } else {
                print("❌ Notification permission denied: \(String(describing: error))")
            }
        }
        
        NSApplication.shared.registerForRemoteNotifications()

        let pop = NSPopover()
        pop.contentSize = NSSize(width: 280, height: 400)
        pop.behavior = .transient
        pop.contentViewController = NSHostingController(rootView: MenuBarView())
        self.popover = pop


        OTPNotificationManager.shared.delegate = self


        PairingManager.shared.$isPaired
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paired in
                self?.updateMenuBarState(show: paired)
                self?.updateDockPolicy()


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

    // MARK: - Dock & Menu Bar

    /// Hides the Dock icon when paired (app lives in menu bar only) and shows it
    /// when unpaired so the user can access onboarding.
    func updateDockPolicy() {


        if PairingManager.shared.isPaired {

             if NSApp.activationPolicy() != .accessory {
                 NSApp.setActivationPolicy(.accessory)
                 print("Dock Policy: ACCESSORY (Paired)")
             }
        } else {

            if NSApp.activationPolicy() != .regular {
                NSApp.setActivationPolicy(.regular)
                print("Dock Policy: REGULAR (Unpaired)")
            }


            DispatchQueue.main.async {
                NSApp.activate(ignoringOtherApps: true)
            }
        }
    }


    /// Creates the NSStatusItem when paired, removes it when unpaired.
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


    /// If a recent OTP exists, re-shows its bubble; otherwise toggles the settings popover.
    @objc func togglePopover(_ sender: AnyObject?) {


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


    /// Uses IOPMAssertion to prevent the system from idle-sleeping while ClipSync is monitoring.
    func preventAppSleep() {
        let reason = "ClipSync needs to monitor clipboard" as CFString
        let success = IOPMAssertionCreateWithName(
            kIOPMAssertionTypePreventUserIdleSystemSleep as CFString,
            IOPMAssertionLevel(kIOPMAssertionLevelOn),
            reason,
            &assertionID
        )

        if success == kIOReturnSuccess {

        } else {

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
    
    
    // MARK: - MessagingDelegate

    /// Receives the refreshed FCM token, stores it in Firestore, and subscribes
    /// to the `all_devices` topic so the Firebase console can broadcast to all Macs.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard let token = fcmToken else { return }
        print("✅ FCM Token received")
        
        // Store token in Firestore
        Task {
            await FCMTokenManager.shared.storeFCMToken(token: token)
        }
        
        // Subscribe to topic so console can target all devices at once
        Messaging.messaging().subscribe(toTopic: "all_devices") { error in
            if let error = error {
                print("❌ Failed to subscribe to all_devices topic: \(error)")
            } else {
                print("✅ Subscribed to all_devices topic")
            }
        }
    }

    /// Hands the APNs device token to Firebase so it can map it to the FCM token.
    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Messaging.messaging().apnsToken = deviceToken
        let tokenHex = deviceToken.map { String(format: "%02.2hhx", $0) }.joined()
        print("✅ APNs token registered: \(tokenHex)")
    }

    func application(_ application: NSApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("❌ APNs registration failed: \(error.localizedDescription)")
    }
    
    
    // MARK: - UNUserNotificationCenterDelegate

    /// Ensures banners and sounds appear even while the app is in the foreground.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Show notification even when app is in foreground
        completionHandler([.banner, .sound, .badge])
    }

    /// Handles a notification tap: if it's an update notification, saves the
    /// update info and fires `.showUpdateDialog` so HomeScreen can present the alert.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        
        if let type = userInfo["type"] as? String, type == "update" {
            let version = userInfo["version"] as? String ?? "Unknown"
            let downloadUrl = userInfo["downloadUrl"] as? String ?? ""
            let releaseNotes = userInfo["releaseNotes"] as? String ?? "New update available!"
            
            UpdateNotificationManager.shared.savePendingUpdate(
                version: version,
                downloadUrl: downloadUrl,
                releaseNotes: releaseNotes
            )
            
            NotificationCenter.default.post(name: .showUpdateDialog, object: nil)
        }
        
        completionHandler()
    }
}


// MARK: - Notification Names

extension Notification.Name {
    static let showUpdateDialog = Notification.Name("showUpdateDialog")
}
