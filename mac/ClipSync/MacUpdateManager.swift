import Foundation
import AppKit
import Combine

class MacUpdateManager: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = MacUpdateManager()
    
    @Published var isCheckingForUpdate = false
    @Published var updateAvailable: GithubRelease? = nil
    @Published var isDownloading = false
    @Published var downloadProgress: Double = 0.0
    @Published var isDownloadComplete = false
    @Published var updateError: String? = nil
    @Published var isUpToDate = false
    
    private var pendingTempDir: URL?
    private var pendingScriptPath: String?
    
    private let repoURL = "https://api.github.com/repos/WinShell-Bhanu/Clipsync/releases/latest"
    private var downloadTask: URLSessionDownloadTask?
    private var session: URLSession!
    
    struct GithubRelease {
        let version: String
        let downloadUrl: String
        let releaseNotes: String
    }
    
    override private init() {
        super.init()
        let config = URLSessionConfiguration.default
        self.session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }
    
    func checkForUpdate(manual: Bool = false) {
        DispatchQueue.main.async {
            guard !self.isCheckingForUpdate && !self.isDownloading else { return }
            
            self.isCheckingForUpdate = true
            self.updateError = nil
            self.isUpToDate = false
            
            guard let url = URL(string: self.repoURL) else {
                self.isCheckingForUpdate = false
                return
            }
            
            var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.setValue("ClipSync-Mac-App", forHTTPHeaderField: "User-Agent")
        
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isCheckingForUpdate = false
                
                if let error = error {
            if manual { self.updateError = "Failed to check for updates: \(error.localizedDescription)" }
            return
        }
        
        guard let data = data else {
            if manual { self.updateError = "Failed to parse update data." }
            return
        }
        
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            if manual { self.updateError = "Failed to parse update data." }
            return
        }
                var tagName = json["tag_name"] as? String ?? ""
                if tagName.lowercased().hasPrefix("v") {
                    tagName = String(tagName.dropFirst())
                }
                
                let releaseNotes = json["body"] as? String ?? "New update available from GitHub!"
                let assets = json["assets"] as? [[String: Any]] ?? []
                
                var downloadUrl = ""
                // We look for a zip file for the mac app
                for asset in assets {
                    let name = (asset["name"] as? String ?? "").lowercased()
                    if name.hasSuffix(".zip") {
                        downloadUrl = asset["browser_download_url"] as? String ?? ""
                        break
                    }
                }
                
                if downloadUrl.isEmpty, let first = assets.first {
                    downloadUrl = first["browser_download_url"] as? String ?? ""
                }
                
                let currentVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0"
                print("🔍 DEBUG: current: \(currentVersion), latest tag: \(tagName)")
                
                if !tagName.isEmpty && !downloadUrl.isEmpty && self.isVersionNewer(currentVersion: currentVersion, newVersion: tagName) {
                    self.updateAvailable = GithubRelease(version: tagName, downloadUrl: downloadUrl, releaseNotes: releaseNotes)
                } else {
                    if manual { self.isUpToDate = true }
                }
            }
        }.resume()
        }
    }
    
    func startDownload(release: GithubRelease) {
        guard let url = URL(string: release.downloadUrl) else { return }
        isDownloading = true
        downloadProgress = 0.0
        isDownloadComplete = false
        updateError = nil
        
        downloadTask = session.downloadTask(with: url)
        downloadTask?.resume()
    }
    
    // MARK: - URLSessionDownloadDelegate
    
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        DispatchQueue.main.async {
            self.downloadProgress = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
        }
    }
    
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let downloadsDir = FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask).first else { return }
        let tempDir = downloadsDir.appendingPathComponent("ClipSync_Update_\(UUID().uuidString)")
        
        do {
            try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true, attributes: nil)
            let zipDest = tempDir.appendingPathComponent("update.zip")
            try FileManager.default.moveItem(at: location, to: zipDest)
            
            // Unzip using unzip command
            let extractTask = Process()
            extractTask.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
            extractTask.arguments = ["-q", zipDest.path, "-d", tempDir.path]
            
            try extractTask.run()
            extractTask.waitUntilExit()
            
            // Remove quarantine attribute so LaunchServices/Terminal can open it without Sandbox blocking it
            let xattrTask = Process()
            xattrTask.executableURL = URL(fileURLWithPath: "/usr/bin/xattr")
            xattrTask.arguments = ["-rc", tempDir.path]
            try? xattrTask.run()
            xattrTask.waitUntilExit()
            
            if extractTask.terminationStatus == 0 {
                // Look for the user's custom script inside the extracted folder recursively
                var foundScriptPath: String? = nil
                if let enumerator = FileManager.default.enumerator(atPath: tempDir.path) {
                    for case let path as String in enumerator {
                        let filename = URL(fileURLWithPath: path).lastPathComponent
                        if filename == "Install ClipSync.command" || filename.hasSuffix(".command") {
                            foundScriptPath = tempDir.appendingPathComponent(path).path
                            // If we found the exact match, stop searching
                            if filename == "Install ClipSync.command" {
                                break
                            }
                        }
                    }
                }
                
                guard let scriptPath = foundScriptPath else {
                    DispatchQueue.main.async {
                        self.isDownloading = false
                        self.updateError = "Could not find 'Install ClipSync.command' in the downloaded archive."
                    }
                    return
                }
                
                // Make the script executable
                var attrs = try FileManager.default.attributesOfItem(atPath: scriptPath)
                attrs[.posixPermissions] = 0o755
                try FileManager.default.setAttributes(attrs, ofItemAtPath: scriptPath)
                
                DispatchQueue.main.async {
                    self.pendingTempDir = tempDir
                    self.pendingScriptPath = scriptPath
                    self.isDownloading = false
                    self.isDownloadComplete = true
                }
                
            } else {
                DispatchQueue.main.async {
                    self.isDownloading = false
                    self.updateError = "Failed to extract the update archive."
                }
            }
            
        } catch {
            DispatchQueue.main.async {
                self.isDownloading = false
                self.updateError = "Error processing update: \(error.localizedDescription)"
            }
        }
    }
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            DispatchQueue.main.async {
                self.isDownloading = false
                self.updateError = "Download failed: \(error.localizedDescription)"
            }
        }
    }
    
    // MARK: - Install Execution
    
    func executeInstall() {
        guard let scriptPath = pendingScriptPath else { return }
        
        // The macOS Sandbox blocks opening .command files in Terminal and blocks elevated NSAppleScript execution.
        // To completely escape the sandbox, we dynamically compile our own temporary, un-sandboxed AppleScript App (.app).
        // This helper app will launch, run the bash script with administrator privileges (prompting natively), and exit.
        
        let escapedPath = scriptPath.replacingOccurrences(of: "\\", with: "\\\\")
                                    .replacingOccurrences(of: "\"", with: "\\\"")
        
        let appleScriptCode = """
        do shell script "bash \\"\(escapedPath)\\"" with administrator privileges
        """
        
        let tempDir = URL(fileURLWithPath: scriptPath).deletingLastPathComponent()
        let appleScriptFile = tempDir.appendingPathComponent("helper.applescript")
        let helperApp = tempDir.appendingPathComponent("ClipSyncUpdaterHelper.app")
        
        do {
            try appleScriptCode.write(to: appleScriptFile, atomically: true, encoding: .utf8)
            
            // Compile the AppleScript into a macOS Application bundle
            let compileTask = Process()
            compileTask.executableURL = URL(fileURLWithPath: "/usr/bin/osacompile")
            compileTask.arguments = ["-o", helperApp.path, appleScriptFile.path]
            try compileTask.run()
            compileTask.waitUntilExit()
            
            if compileTask.terminationStatus == 0 {
                // Strip quarantine metadata from the newly created helper app
                let xattrTask = Process()
                xattrTask.executableURL = URL(fileURLWithPath: "/usr/bin/xattr")
                xattrTask.arguments = ["-rc", helperApp.path]
                try? xattrTask.run()
                xattrTask.waitUntilExit()
                
                // Launch our un-sandboxed helper app
                NSWorkspace.shared.open(helperApp)
                NSApplication.shared.terminate(nil)
            } else {
                self.updateError = "Failed to compile updater helper app."
            }
        } catch {
            self.updateError = "Failed to create updater helper: \(error.localizedDescription)"
        }
    }
    
    func cancelInstall() {
        if let tempDir = pendingTempDir {
            try? FileManager.default.removeItem(at: tempDir)
        }
        
        isDownloading = false
        isDownloadComplete = false
        updateAvailable = nil
        pendingTempDir = nil
        pendingScriptPath = nil
        downloadProgress = 0.0
    }
    
    // MARK: - Version Comparison
    
    private func isVersionNewer(currentVersion: String, newVersion: String) -> Bool {
        let cur = currentVersion.hasPrefix("v") ? String(currentVersion.dropFirst()) : currentVersion
        let new = newVersion.hasPrefix("v") ? String(newVersion.dropFirst()) : newVersion
        
        let curParts = cur.components(separatedBy: "-")
        let newParts = new.components(separatedBy: "-")
        
        let curNums = curParts[0].split(separator: ".").compactMap { Int($0) }
        let newNums = newParts[0].split(separator: ".").compactMap { Int($0) }
        
        let maxLength = max(curNums.count, newNums.count)
        
        for i in 0..<maxLength {
            let curPart = i < curNums.count ? curNums[i] : 0
            let newPart = i < newNums.count ? newNums[i] : 0
            
            if newPart > curPart { return true }
            if newPart < curPart { return false }
        }
        
        // Numeric parts are exactly the same.
        // A version WITH a pre-release tag is OLDER than a version WITHOUT one (e.g., 3.0.0-beta < 3.0.0).
        let curIsPre = curParts.count > 1
        let newIsPre = newParts.count > 1
        
        if curIsPre && !newIsPre {
            return true
        } else if !curIsPre && newIsPre {
            return false
        } else if curIsPre && newIsPre {
            // Both are pre-release, compare tags lexicographically (e.g., "beta" > "alpha")
            return newParts[1] > curParts[1]
        }
        
        return false
    }
}
