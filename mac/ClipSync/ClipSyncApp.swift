


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

    /// Registers default preferences, migrates secrets to Keychain, initialises Firebase,
    /// and resumes clipboard sync/listening if a valid pairing was previously saved.
    init() {

        // M1: migrate legacy UserDefaults secrets to Keychain (idempotent, safe to call every launch)
        KeychainHelper.migrateFromUserDefaults(udKey: "encryption_key", keychainAccount: "encryption_key")
        KeychainHelper.migrateFromUserDefaults(udKey: "current_pairing_id", keychainAccount: "current_pairing_id")

        UserDefaults.standard.register(defaults: [
            "syncToMac": true,
            "syncFromMac": true
        ])

        let isLocalOnlyMode = UserDefaults.standard.string(forKey: "sync_mode") == "local"
        if !isLocalOnlyMode {
            _ = FirebaseManager.shared
        }
        PairingManager.shared.restorePairing()

        if PairingManager.shared.isPaired {
             ClipboardManager.shared.startMonitoring()
             if !isLocalOnlyMode {
                 ClipboardManager.shared.listenForAndroidClipboard()
             }
             // Start the always-on TCP server and BLE wakeup receiver
             ClipSyncServer.shared.start()
             WakeupReceiver.shared.start()
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




// MARK: - AppDelegate

/// Wires up FCM messaging, push notifications, the menu bar status item, and
/// the OTP popover. Also manages the Dock icon visibility based on paired state.
class AppDelegate: NSObject, NSApplicationDelegate, MessagingDelegate, UNUserNotificationCenterDelegate, OTPNotificationDelegate {

    // MARK: - Properties

    var statusItem: NSStatusItem?
    var popover: NSPopover?
    var cancellables = Set<AnyCancellable>()
    var assertionID: IOPMAssertionID = 0
    var globalEventMonitor: Any?

    // MARK: - Lifecycle

    /// Boots FCM, notification permissions, builds the popover, and starts
    /// observing pairing state to show/hide the menu bar icon.
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Process any files shared while the app was closed,
        // then observe for new shares arriving while running.
        processPendingBookmarks()
        setupShareExtensionListener()
        
        // Listen for update notifications globally so background checks or pushes can trigger the UI
        NotificationCenter.default.addObserver(forName: .showUpdateDialog, object: nil, queue: .main) { _ in
            UpdateWindowController.shared.showWindow()
        }
        
        // Automatically check for updates silently in the background
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) {
            MacUpdateManager.shared.checkForUpdate(manual: false)
        }
        
        let isLocalOnlyMode = UserDefaults.standard.string(forKey: "sync_mode") == "local"

        if !isLocalOnlyMode {
            // Set FCM delegate
            Messaging.messaging().delegate = self
        }
        
        UNUserNotificationCenter.current().delegate = self
        
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .badge, .sound]) { granted, error in
            // Handle notification permission result
        }
        
        if !isLocalOnlyMode {
            NSApplication.shared.registerForRemoteNotifications()
        }

        let pop = NSPopover()
        pop.contentSize = NSSize(width: 280, height: 400)
        pop.behavior = .transient
        let hostingController = NSHostingController(rootView: MenuBarView())
        pop.contentViewController = hostingController
        self.popover = pop

        // Force view load to eliminate first-click lag
        _ = hostingController.view

        // Listen for clicks outside the app to close the popover
        globalEventMonitor = NSEvent.addGlobalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown]) { [weak self] event in
            if self?.popover?.isShown == true {
                self?.popover?.performClose(event)
            }
        }


        OTPNotificationManager.shared.delegate = self


        PairingManager.shared.$isPaired
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paired in
                self?.updateMenuBarState(show: paired)
                self?.updateDockPolicy()


                if paired && UserDefaults.standard.string(forKey: "sync_mode") != "local" {
                    OTPNotificationManager.shared.startListening()
                } else {
                    OTPNotificationManager.shared.stopListening()
                }
            }
            .store(in: &cancellables)
            
        // Observe transfer state to prevent sleep
        Publishers.CombineLatest(ClipSyncServer.shared.$hasActiveClient, ClipSyncServer.shared.$isSendingFile)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] hasClient, isSending in
                if hasClient || isSending {
                    self?.preventAppSleep()
                } else {
                    self?.allowAppSleep()
                }
            }
            .store(in: &cancellables)
    }

    /// Observes the Darwin notification posted by ClipSyncShare extension.
    /// UserDefaults.didChangeNotification is in-process only and NEVER fires
    /// when a separate process (the extension) writes to shared defaults.
    /// Darwin notifications (CFNotificationCenter) cross process boundaries.
    private func setupShareExtensionListener() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(
            center,
            observer,
            { _, observer, _, _, _ in
                guard let observer else { return }
                let delegate = Unmanaged<AppDelegate>.fromOpaque(observer).takeUnretainedValue()
                DispatchQueue.main.async { delegate.processPendingBookmarks() }
            },
            "com.OP.ClipSync.share.pendingFiles" as CFString,
            nil,
            .deliverImmediately
        )
    }

    /// Resolves any pending bookmark Data objects from the shared App Group
    /// into security-scoped URLs and kicks off a file send.
    func processPendingBookmarks() {
        let urls = SecurityScopedResourceManager.resolveAndConsume()
        guard !urls.isEmpty else { return }

        ClipSyncServer.shared.sendFiles(urls: urls)

        // Stop security-scoped access once the server has handed off the URLs.
        // ClipSyncServer reads the file synchronously during sendFiles setup,
        // so we can release access after a short delay.
        DispatchQueue.global().asyncAfter(deadline: .now() + 2) {
            SecurityScopedResourceManager.stopAccessing(urls)
        }

        // Show popover so user sees transfer progress
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if !(self.popover?.isShown ?? false), let button = self.statusItem?.button {
                self.popover?.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
                NSApp.activate(ignoringOtherApps: true)
            }
        }
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
             }
        } else {

            if NSApp.activationPolicy() != .regular {
                NSApp.setActivationPolicy(.regular)
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
        if assertionID != 0 { return } // Already preventing sleep
        
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

    func allowAppSleep() {
        if assertionID != 0 {
            IOPMAssertionRelease(assertionID)
            assertionID = 0
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

    /// Receives the refreshed FCM token and stores it in Firestore.
    /// C3 fix: removed all_devices topic subscription — it's a phishing vector.
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        guard UserDefaults.standard.string(forKey: "sync_mode") != "local" else {
            return
        }
        guard let token = fcmToken else { return }
        
        // Store token in Firestore
        Task {
            await FCMTokenManager.shared.storeFCMToken(token: token)
        }
    }

    /// Hands the APNs device token to Firebase so it can map it to the FCM token.
    /// M8 fix: no longer logs the raw APNs token hex in cleartext.
    func application(_ application: NSApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        guard UserDefaults.standard.string(forKey: "sync_mode") != "local" else { return }
        Messaging.messaging().apnsToken = deviceToken
    }

    func application(_ application: NSApplication, didFailToRegisterForRemoteNotificationsWithError error: Error) {
        // Handle failure silently in production
    }
    
    /// Handles incoming push notifications while the app is running.
    func application(_ application: NSApplication, didReceiveRemoteNotification userInfo: [String : Any]) {
        if let type = userInfo["type"] as? String, type == "wake_up" {
            // Fetch latest clipboard immediately
            ClipboardManager.shared.pullClipboard()
        }
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
        
        if let type = userInfo["type"] as? String {
            if type == "update" {
                let version = userInfo["version"] as? String ?? "Unknown"
                let downloadUrl = userInfo["downloadUrl"] as? String ?? ""
                let releaseNotes = userInfo["releaseNotes"] as? String ?? "New update available!"
                
                UpdateNotificationManager.shared.savePendingUpdate(
                    version: version,
                    downloadUrl: downloadUrl,
                    releaseNotes: releaseNotes
                )
                
                NotificationCenter.default.post(name: .showUpdateDialog, object: nil)
            } else if type == "file" {
                if let path = userInfo["path"] as? String {
                    let url = URL(fileURLWithPath: path)
                    NSWorkspace.shared.selectFile(url.path, inFileViewerRootedAtPath: "")
                }
            }
        }
        
        completionHandler()
    }
}


// MARK: - Notification Names

extension Notification.Name {
    static let showUpdateDialog = Notification.Name("showUpdateDialog")
}
