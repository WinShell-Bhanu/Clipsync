import Foundation

/// Resolves minimal bookmark Data (written by ClipSyncShare extension) back
/// into security-scoped URLs the main app can read and send.
///
/// Usage:
///   let urls = SecurityScopedResourceManager.resolveAndConsume()
///   defer { SecurityScopedResourceManager.stopAccessing(urls) }
///   ClipSyncServer.shared.sendFiles(urls: urls)
enum SecurityScopedResourceManager {

    private static let appGroupID  = "group.com.OP.ClipSync"
    private static let defaultsKey = "pendingBookmarks"

    /// Reads all pending bookmark Data values from shared UserDefaults,
    /// resolves each into a URL, starts security-scoped access, clears the
    /// queue, and returns the live URLs.
    ///
    /// Call `stopAccessing(_:)` when the files are no longer needed.
    static func resolveAndConsume() -> [URL] {
        guard let defaults = UserDefaults(suiteName: appGroupID) else { return [] }
        let bookmarks = defaults.array(forKey: defaultsKey) as? [Data] ?? []
        guard !bookmarks.isEmpty else { return [] }

        // Clear queue immediately — prevents double-processing if app is
        // relaunched before the transfer completes.
        defaults.removeObject(forKey: defaultsKey)
        defaults.synchronize()

        var resolved: [URL] = []
        for bookmark in bookmarks {
            var isStale = false
            guard
                let url = try? URL(resolvingBookmarkData: bookmark,
                                   options: .withoutUI,
                                   relativeTo: nil,
                                   bookmarkDataIsStale: &isStale)
            else { continue }

            if url.startAccessingSecurityScopedResource() {
                resolved.append(url)
            }
        }
        return resolved
    }

    /// Stops security-scoped access for every URL returned by `resolveAndConsume`.
    /// Call this after the file transfer is complete (or fails).
    static func stopAccessing(_ urls: [URL]) {
        urls.forEach { $0.stopAccessingSecurityScopedResource() }
    }
}
