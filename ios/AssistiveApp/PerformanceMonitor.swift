import Foundation
import UIKit

class PerformanceMonitor {
    static let shared = PerformanceMonitor()
    
    func getMemoryUsageMB() -> Float {
        var info = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size) / 4
        
        let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count)
            }
        }
        
        if kerr == KERN_SUCCESS {
            return Float(info.resident_size) / 1024.0 / 1024.0
        } else {
            return 0.0
        }
    }
    
    func getTemperatureCelsius() -> Float {
        let state = ProcessInfo.processInfo.thermalState
        switch state {
        case .nominal:
            return 36.0
        case .fair:
            return 40.0
        case .serious:
            return 48.0
        case .critical:
            return 58.0
        @unknown default:
            return 36.0
        }
    }
    
    func getBatteryDrainMahPerMin() -> Float {
        UIDevice.current.isBatteryMonitoringEnabled = true
        let batteryLevel = UIDevice.current.batteryLevel
        if batteryLevel < 0 { return 4.0 }
        
        // Simulating current load in mAh per minute based on device state
        let state = ProcessInfo.processInfo.thermalState
        switch state {
        case .nominal: return 3.5
        case .fair: return 5.8
        case .serious: return 9.2
        case .critical: return 14.5
        @unknown default: return 3.5
        }
    }
}
