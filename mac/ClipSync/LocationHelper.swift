//
//  LocationHelper.swift
//  ClipSync
//

import Foundation

class LocationHelper {
    static let shared = LocationHelper()
    
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
