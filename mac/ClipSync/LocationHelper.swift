


import Foundation


// Purpose: Class that models location helper behavior in this module.
// Responsibilities: Encapsulates location helper behavior for this feature area.
// Usage: Start here to understand how this file contributes to app-level flow.
class LocationHelper {
    static let shared = LocationHelper()


    // Purpose: Implements the detect region operation for this feature.
    // Parameters: completion.
    // Returns: Void).
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    func detectRegion(completion: @escaping (String?) -> Void) {
        let primaryUrl = URL(string: "https://ip-api.com/json/")!

        let task = URLSession.shared.dataTask(with: primaryUrl) { [weak self] data, response, error in
            if let data = data, error == nil,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let countryCode = json["countryCode"] as? String {
                completion(countryCode)
                return
            }

            self?.detectRegionFallback(completion: completion)
        }
        task.resume()
    }


    // Purpose: Implements the detect region fallback operation for this feature.
    // Parameters: completion.
    // Returns: Void).
    // Notes: Keep logic cohesive and avoid hidden side effects outside this scope.
    private func detectRegionFallback(completion: @escaping (String?) -> Void) {
        guard let url = URL(string: "https://api.country.is") else {
            completion(nil)
            return
        }

        let task = URLSession.shared.dataTask(with: url) { data, response, error in
            if let data = data, error == nil,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let countryCode = json["country"] as? String {
                completion(countryCode)
            } else {
                completion(nil)
            }
        }
        task.resume()
    }
}
