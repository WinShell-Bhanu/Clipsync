


import SwiftUI
import FirebaseCore
import FirebaseMessaging
import AppKit
import Combine
import IOKit.pwr_mgt
import UserNotifications

@main


// Purpose: Struct that models clip sync app behavior in this module.
// Responsibilities: Encapsulates clip sync app behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct ClipSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @ObservedObject private var pairingManager = PairingManager.shared


    // Purpose: Initializes the type with required runtime state.
    // Parameters: No parameters.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
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


// Purpose: Struct that models window configurator behavior in this module.
// Responsibilities: Encapsulates window configurator behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
private struct WindowConfigurator: NSViewRepresentable {
    let configure: (NSWindow) -> Void


    // Purpose: Initializes the type with required runtime state.
    // Parameters: configure.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
    init(_ configure: @escaping (NSWindow) -> Void) {
        self.configure = configure
    }


    // Purpose: Implements the make nsview operation for this feature.
    // Parameters: context.
    // Returns: NSView.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func makeNSView(context: Context) -> NSView {
        let view = NSView(frame: .zero)
        DispatchQueue.main.async { [weak view] in
            if let win = view?.window { configure(win) }
        }
        return view
    }


    // Purpose: Updates nsview based on current inputs.
    // Parameters: nsView, context.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func updateNSView(_ nsView: NSView, context: Context) {
        DispatchQueue.main.async { [weak nsView] in
            if let win = nsView?.window { configure(win) }
        }
    }
}


#if canImport(HotSwiftUI)
@_exported import HotSwiftUI
#elseif canImport(Inject)
@_exported import Inject
#else
#if DEBUG
import Combine


// Purpose: Class that models injection observer behavior in this module.
// Responsibilities: Encapsulates injection observer behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
public class InjectionObserver: ObservableObject {
    public static let shared = InjectionObserver()
    @Published var injectionNumber = 0
    var cancellable: AnyCancellable? = nil
    let publisher = PassthroughSubject<Void, Never>()


    // Purpose: Initializes the type with required runtime state.
    // Parameters: No parameters.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
    init() {
        cancellable = NotificationCenter.default.publisher(for:
            Notification.Name("INJECTION_BUNDLE_NOTIFICATION"))
            .sink { [weak self] change in
            self?.injectionNumber += 1
            self?.publisher.send()
        }
    }
}


// Purpose: Extension that adds focused behavior to an existing type.
// Responsibilities: Encapsulates swift ui behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
extension SwiftUI.View {


    // Purpose: Implements the erase to any view operation for this feature.
    // Parameters: No parameters.
    // Returns: some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func eraseToAnyView() -> some SwiftUI.View {
        return AnyView(self)
    }


    // Purpose: Implements the enable injection operation for this feature.
    // Parameters: No parameters.
    // Returns: some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func enableInjection() -> some SwiftUI.View {
        return eraseToAnyView()
    }


    // Purpose: Handles the on injection callback path.
    // Parameters: bumpState.
    // Returns: ()) -> some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func onInjection(bumpState: @escaping () -> ()) -> some SwiftUI.View {
        return self
            .onReceive(InjectionObserver.shared.publisher, perform: bumpState)
            .eraseToAnyView()
    }
}

@available(iOS 13.0, macOS 10.15, tvOS 13.0, watchOS 6.0, *)
@propertyWrapper


// Purpose: Struct that models observe injection behavior in this module.
// Responsibilities: Encapsulates observe injection behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
public struct ObserveInjection: DynamicProperty {
    @ObservedObject private var iO = InjectionObserver.shared


    // Purpose: Initializes the type with required runtime state.
    // Parameters: No parameters.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
    public init() {}
    public private(set) var wrappedValue: Int {
        get {0} set {}
    }
}
#else


// Purpose: Extension that adds focused behavior to an existing type.
// Responsibilities: Encapsulates swift ui behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
extension SwiftUI.View {
    @inline(__always)


    // Purpose: Implements the erase to any view operation for this feature.
    // Parameters: No parameters.
    // Returns: some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func eraseToAnyView() -> some SwiftUI.View { return self }
    @inline(__always)


    // Purpose: Implements the enable injection operation for this feature.
    // Parameters: No parameters.
    // Returns: some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func enableInjection() -> some SwiftUI.View { return self }
    @inline(__always)


    // Purpose: Handles the on injection callback path.
    // Parameters: bumpState.
    // Returns: ()) -> some SwiftUI.View.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    public func onInjection(bumpState: @escaping () -> ()) -> some SwiftUI.View {
        return self
    }
}

@available(iOS 13.0, macOS 10.15, tvOS 13.0, watchOS 6.0, *)
@propertyWrapper


// Purpose: Struct that models observe injection behavior in this module.
// Responsibilities: Encapsulates observe injection behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
public struct ObserveInjection {


    // Purpose: Initializes the type with required runtime state.
    // Parameters: No parameters.
    // Returns: New initialized instance.
    // Notes: Keep initialization lightweight and defer heavy work when possible.
    public init() {}
    public private(set) var wrappedValue: Int {
        get {0} set {}
    }
}
#endif
#endif


// Purpose: Class that models app delegate behavior in this module.
// Responsibilities: Encapsulates app delegate behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class AppDelegate: NSObject, NSApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate, OTPNotificationDelegate {
    var statusItem: NSStatusItem?
    var popover: NSPopover?
    var cancellables = Set<AnyCancellable>()
    var assertionID: IOPMAssertionID = 0


    // Purpose: Implements the application did finish launching operation for this feature.
    // Parameters: notification.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func applicationDidFinishLaunching(_ notification: Notification) {

        // Set FCM delegate
        Messaging.messaging().delegate = self
        
        // Set notification center delegate
        UNUserNotificationCenter.current().delegate = self
        
        // Request notification permissions
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            if granted {
                print("✅ Notification permission granted")
            } else {
                print("❌ Notification permission denied: \(String(describing: error))")
            }
        }
        
        // Register for remote notifications
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


    // Purpose: Implements the application will terminate operation for this feature.
    // Parameters: notification.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func applicationWillTerminate(_ notification: Notification) {
        OTPNotificationManager.shared.stopListening()
    }


    // Purpose: Updates dock policy based on current inputs.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Updates menu bar state based on current inputs.
    // Parameters: show.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the toggle popover operation for this feature.
    // Parameters: sender.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the prevent app sleep operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
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


    // Purpose: Implements the application should terminate after last window closed operation for this feature.
    // Parameters: sender.
    // Returns: Bool.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }


    // Purpose: Finalizes the instance before deallocation.
    // Parameters: No external parameters.
    // Returns: Void.
    // Notes: Release observers, timers, and retained resources here.
    deinit {
        NotificationCenter.default.removeObserver(self)
        if assertionID != 0 {
            IOPMAssertionRelease(assertionID)
        }
    }
    
    
    // MARK: - MessagingDelegate
    
    // Purpose: Called when FCM token is refreshed.
    // Parameters: messaging, fcmToken.
    // Returns: Void unless returned explicitly.
    // Notes: Stores token in Firestore for manual notification sending.
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
    
    
    // MARK: - UNUserNotificationCenterDelegate
    
    // Purpose: Called when notification is received while app is in foreground.
    // Parameters: center, notification, completionHandler.
    // Returns: Void unless returned explicitly.
    // Notes: Shows notification even when app is open.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Show notification even when app is in foreground
        completionHandler([.banner, .sound, .badge])
    }
    
    
    // Purpose: Called when user taps on a notification.
    // Parameters: center, response, completionHandler.
    // Returns: Void unless returned explicitly.
    // Notes: Opens app and shows update dialog.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let userInfo = response.notification.request.content.userInfo
        
        // Check if this is an update notification
        if let type = userInfo["type"] as? String, type == "update" {
            let version = userInfo["version"] as? String ?? "Unknown"
            let downloadUrl = userInfo["downloadUrl"] as? String ?? ""
            let releaseNotes = userInfo["releaseNotes"] as? String ?? "New update available!"
            
            // Save pending update for dialog
            UpdateNotificationManager.shared.savePendingUpdate(
                version: version,
                downloadUrl: downloadUrl,
                releaseNotes: releaseNotes
            )
            
            // Notify observers to show dialog
            NotificationCenter.default.post(name: .showUpdateDialog, object: nil)
        }
        
        completionHandler()
    }
}


// Purpose: Notification name for update dialog trigger.
// Responsibilities: Used to communicate between AppDelegate and SwiftUI views.
// Usage: Post when notification is tapped, observe in HomeScreen.
extension Notification.Name {
    static let showUpdateDialog = Notification.Name("showUpdateDialog")
}
