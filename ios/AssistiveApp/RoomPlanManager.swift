import ARKit
import Foundation

#if canImport(RoomPlan)
import RoomPlan

@available(iOS 16.0, *)
class RoomPlanManager: NSObject, RoomCaptureSessionDelegate {
    static let shared = RoomPlanManager()
    
    private(set) var session: RoomCaptureSession?
    private var lastAnnouncedTime: Double = 0.0
    private let announcementInterval: Double = 4.0 // Announce every 4 seconds to limit audio overhead
    
    func startSession() {
        guard DeviceCapabilities.supportsLiDAR() else {
            LogStore.shared.log("[RoomPlan] LiDAR not supported on this device. RoomPlan disabled.")
            return
        }
        
        // Start ARDepthPipeline with lower FPS (0.50s throttle interval) before RoomCaptureSession
        ARDepthPipeline.shared.throttleInterval = 0.50
        ARDepthPipeline.shared.startSession()
        
        LogStore.shared.log("[RoomPlan] Starting RoomCaptureSession...")
        let session = RoomCaptureSession()
        session.delegate = self
        self.session = session
        
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
    }
    
    func stopSession() {
        session?.stop()
        session = nil
        
        // Stop ARDepthPipeline and restore throttleInterval
        ARDepthPipeline.shared.stopSession()
        ARDepthPipeline.shared.throttleInterval = 0.20
    }
    
    func pauseSession() {
        session?.stop()
        ARDepthPipeline.shared.pauseSession()
        LogStore.shared.log("[RoomPlan] Session paused (GPU yield).")
    }
    
    func resumeSession() {
        guard let session = self.session else { return }
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
        ARDepthPipeline.shared.resumeSession()
        LogStore.shared.log("[RoomPlan] Session resumed.")
    }
    
    func roomCaptureSession(_ session: RoomCaptureSession, didUpdate room: CapturedRoom) {
        let now = CACurrentMediaTime()
        guard now - lastAnnouncedTime >= announcementInterval else { return }
        
        var doorAnnouncements: [String] = []
        for door in room.doors {
            // Transform matrix columns.3 gives the 3D position relative to origin
            let doorPos = simd_make_float3(door.transform.columns.3.x, door.transform.columns.3.y, door.transform.columns.3.z)
            let distance = simd_distance(doorPos, simd_make_float3(0, 0, 0))
            
            var isOpenText = "ปิดอยู่"
            if case let .door(isOpen) = door.category {
                isOpenText = isOpen ? "เปิดอยู่" : "ปิดอยู่"
            } else {
                // Heuristic: check transform rotation angle around Y-axis
                // Extraction of rotation around Y axis from rotation matrix
                let angle = atan2(door.transform.columns.0.z, door.transform.columns.0.x)
                // If rotated significantly, treat as open
                isOpenText = abs(angle) > 0.1 ? "เปิดอยู่" : "ปิดอยู่"
            }
            
            doorAnnouncements.append(String(format: "พบประตู ห่าง %.1f เมตร สถานะ %@", distance, isOpenText))
        }
        
        var furnitureAnnouncements: [String] = []
        for object in room.objects {
            if object.category == .chair || object.category == .sofa {
                let objectPos = simd_make_float3(object.transform.columns.3.x, object.transform.columns.3.y, object.transform.columns.3.z)
                let distance = simd_distance(objectPos, simd_make_float3(0, 0, 0))
                
                let isOccupied = checkOccupancy(for: object)
                let statusText = isOccupied ? "ไม่ว่าง" : "เก้าอี้ว่าง"
                
                let categoryName = object.category == .chair ? "เก้าอี้" : "โซฟา"
                furnitureAnnouncements.append(String(format: "พบ%@ ห่าง %.1f เมตร สถานะ %@", categoryName, distance, statusText))
            }
        }
        
        // Speak results
        var announcement = ""
        if let firstDoor = doorAnnouncements.first {
            announcement = firstDoor
            lastAnnouncedTime = now
            AudioPipeline.shared.speak(firstDoor)
            HapticManager.shared.vibrateGeneralInfo()
        } else if let firstFurniture = furnitureAnnouncements.first {
            announcement = firstFurniture
            lastAnnouncedTime = now
            AudioPipeline.shared.speak(firstFurniture)
            HapticManager.shared.vibrateGeneralInfo()
        }
        
        if !announcement.isEmpty {
            let userInfo: [AnyHashable: Any] = [
                "aiResult": announcement,
                "status": "กำลังสแกนห้อง..."
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
        } else {
            let userInfo: [AnyHashable: Any] = [
                "aiResult": "ยังไม่พบประตูหรือเก้าอี้ใกล้ตัว",
                "status": "กำลังสแกนหาวัตถุ..."
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
        }
    }
    
    private func checkOccupancy(for object: CapturedRoom.Object) -> Bool {
        let peopleLocations = ARDepthPipeline.shared.lastDetectedPeopleWorldPositions
        let chairPos = simd_make_float3(object.transform.columns.3.x, object.transform.columns.3.y, object.transform.columns.3.z)
        
        for personPos in peopleLocations {
            let dist = simd_distance(chairPos, personPos)
            if dist < 0.8 { // 0.8 meters threshold
                return true
            }
        }
        return false
    }
}
#endif

class DeviceCapabilities {
    static func supportsLiDAR() -> Bool {
        #if canImport(RoomPlan)
        if #available(iOS 16.0, *) {
            return ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)
        }
        #endif
        return false
    }
}
