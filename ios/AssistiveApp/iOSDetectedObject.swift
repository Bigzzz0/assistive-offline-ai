import Foundation
import CoreGraphics

struct iOSDetectedObject: Identifiable, Equatable {
    let id = UUID()
    let label: String
    let labelThai: String
    let boundingBox: CGRect // Normalized [0, 1] bottom-left origin (Vision coordinates)
    let distance: Float // Distance in meters, or negative if unavailable
    
    static func == (lhs: iOSDetectedObject, rhs: iOSDetectedObject) -> Bool {
        return lhs.label == rhs.label &&
               lhs.labelThai == rhs.labelThai &&
               lhs.boundingBox == rhs.boundingBox &&
               lhs.distance == rhs.distance
    }
}
