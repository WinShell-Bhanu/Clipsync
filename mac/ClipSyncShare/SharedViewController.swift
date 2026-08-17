import Cocoa

/// ClipSync Share Extension
///
/// Flow:
///   1. Receive file attachments from macOS Share sheet.
///   2. For each file, create a *minimal* bookmark (not security-scoped —
///      those can't cross the App Group boundary) and store it in shared
///      UserDefaults so the main app can resolve it.
///   3. Launch the main ClipSync app if it isn't already running.
///   4. Complete the extension request.
///
/// The main app observes the `pendingBookmarks` UserDefaults key and picks
/// up the files once it wakes. Using bookmarks instead of copying files means
/// zero disk overhead and no memory-limit crashes on large files.
class ShareViewController: NSViewController {

    private let appGroupID  = "group.com.OP.ClipSync"
    private let defaultsKey = "pendingBookmarks"
    private let mainAppBundleID = "com.OP.ClipSync"

    override func loadView() {
        self.view = NSView()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        processAttachments()
    }

    // MARK: - Core

    private func processAttachments() {
        guard
            let extensionItem = extensionContext?.inputItems.first as? NSExtensionItem,
            let attachments = extensionItem.attachments,
            !attachments.isEmpty
        else {
            cancel(reason: "No attachments found.")
            return
        }

        let group = DispatchGroup()
        var bookmarks: [Data] = []
        let lock = NSLock()

        for provider in attachments {
            guard provider.hasItemConformingToTypeIdentifier("public.file-url") else { continue }
            group.enter()
            provider.loadItem(forTypeIdentifier: "public.file-url", options: nil) { item, error in
                defer { group.leave() }
                guard error == nil, let url = Self.url(from: item) else { return }

                // Minimal bookmark — crosses App Group boundary safely.
                // The main app resolves it and calls startAccessingSecurityScopedResource().
                if let bookmark = try? url.bookmarkData(options: .minimalBookmark,
                                                        includingResourceValuesForKeys: nil,
                                                        relativeTo: nil) {
                    lock.lock()
                    bookmarks.append(bookmark)
                    lock.unlock()
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            guard !bookmarks.isEmpty else {
                self.cancel(reason: "No valid file bookmarks created.")
                return
            }
            self.enqueue(bookmarks: bookmarks)
            self.launchMainApp()
            self.extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
        }
    }

    // MARK: - Helpers

    /// Writes bookmark data into the shared UserDefaults suite, then posts a
    /// Darwin notification to wake the main app across the process boundary.
    /// UserDefaults.didChangeNotification is in-process only — Darwin
    /// notifications are the correct IPC mechanism here.
    private func enqueue(bookmarks: [Data]) {
        guard let defaults = UserDefaults(suiteName: appGroupID) else { return }
        var existing = defaults.array(forKey: defaultsKey) as? [Data] ?? []
        existing.append(contentsOf: bookmarks)
        defaults.set(existing, forKey: defaultsKey)
        defaults.synchronize()

        // Wake the main app via Darwin notification (crosses process boundaries).
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        CFNotificationCenterPostNotification(
            center,
            CFNotificationName("com.OP.ClipSync.share.pendingFiles" as CFString),
            nil, nil, true
        )
    }

    /// Opens the main ClipSync app via NSWorkspace. Works whether the app is
    /// running (brings it to front) or not (cold-launches it).
    private func launchMainApp() {
        let workspace = NSWorkspace.shared
        // Prefer running instance first
        if let app = NSRunningApplication.runningApplications(withBundleIdentifier: mainAppBundleID).first {
            app.activate(options: [])
            return
        }
        // Cold-launch via bundle ID
        if #available(macOS 10.15, *) {
            let config = NSWorkspace.OpenConfiguration()
            config.activates = true
            if let url = workspace.urlForApplication(withBundleIdentifier: mainAppBundleID) {
                workspace.openApplication(at: url, configuration: config, completionHandler: nil)
            }
        } else {
            workspace.launchApplication(withBundleIdentifier: mainAppBundleID,
                                        options: [],
                                        additionalEventParamDescriptor: nil,
                                        launchIdentifier: nil)
        }
    }

    private func cancel(reason: String) {
        extensionContext?.cancelRequest(withError: NSError(
            domain: "com.OP.ClipSync.share",
            code: -1,
            userInfo: [NSLocalizedDescriptionKey: reason]
        ))
    }

    // MARK: - URL extraction

    private static func url(from item: NSSecureCoding?) -> URL? {
        if let url = item as? URL { return url }
        if let data = item as? Data { return URL(dataRepresentation: data, relativeTo: nil) }
        return nil
    }
}

