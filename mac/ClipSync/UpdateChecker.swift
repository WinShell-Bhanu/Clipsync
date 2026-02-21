import Foundation
import Combine


// Purpose: Struct that models git hub release behavior in this module.
// Responsibilities: Encapsulates git hub release behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
struct GitHubRelease: Decodable {
    let tag_name: String
    let html_url: String
    let body: String?
}


// Purpose: Class that models update checker behavior in this module.
// Responsibilities: Encapsulates update checker behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class UpdateChecker: ObservableObject {
    static let shared = UpdateChecker()

    @Published var updateAvailable: Bool = false
    @Published var latestVersion: String = ""
    @Published var downloadURL: URL?
    @Published var releaseNotes: String = ""

    private let repoOwner = "WinShell-Bhanu"
    private let repoName = "Clipsync"
    private let currentVersion = "1.0.0"


    // Purpose: Implements the check for updates operation for this feature.
    // Parameters: No parameters.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func checkForUpdates() {
        let urlString = "https://api.github.com/repos/\(repoOwner)/\(repoName)/releases?per_page=1"
        guard let url = URL(string: urlString) else { return }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("ClipSync-Mac-App", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let data = data, error == nil else { return }

            do {
                let releases = try JSONDecoder().decode([GitHubRelease].self, from: data)

                if let latestRelease = releases.first {
                    DispatchQueue.main.async {
                        self?.compareVersions(latestTag: latestRelease.tag_name, release: latestRelease)
                    }
                }
            } catch {
            }
        }.resume()
    }


    // Purpose: Implements the compare versions operation for this feature.
    // Parameters: latestTag, release.
    // Returns: Void unless returned explicitly.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func compareVersions(latestTag: String, release: GitHubRelease) {
        let cleanLatest = latestTag.replacingOccurrences(of: "v", with: "")
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? currentVersion
        let cleanCurrent = appVersion.replacingOccurrences(of: "v", with: "")

        if isVersionNewer(current: cleanCurrent, latest: cleanLatest) {
            self.latestVersion = latestTag
            self.downloadURL = URL(string: release.html_url)
            self.releaseNotes = release.body ?? "New update available!"
            self.updateAvailable = true
        }
    }


    // Purpose: Evaluates whether is version newer.
    // Parameters: current, latest.
    // Returns: Bool.
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func isVersionNewer(current: String, latest: String) -> Bool {
        let currentParts = current.split(separator: ".").compactMap { Int($0) }
        let latestParts = latest.split(separator: ".").compactMap { Int($0) }

        let length = max(currentParts.count, latestParts.count)

        for i in 0..<length {
            let c = i < currentParts.count ? currentParts[i] : 0
            let l = i < latestParts.count ? latestParts[i] : 0

            if l > c { return true }
            if l < c { return false }
        }
        return false
    }
}
