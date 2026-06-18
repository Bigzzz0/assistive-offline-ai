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
    
    private var mockTimer: Timer?
    private var mockDistance: Float = 3.0
    
    func startSession() {
        guard DeviceCapabilities.supportsLiDAR() else {
            LogStore.shared.log("[RoomPlan] LiDAR not supported on this device. Starting simulated RoomPlan...")
            startMockSession()
            return
        }
        
        // Enable ARDepthPipeline frame processing and set adaptive throttle
        ARDepthPipeline.shared.throttleInterval = 0.50
        
        LogStore.shared.log("[RoomPlan] Starting RoomCaptureSession...")
        let session = RoomCaptureSession()
        session.delegate = self
        
        // Share the RoomCaptureSession's ARSession with ARDepthPipeline to prevent GPU/camera conflicts
        ARDepthPipeline.shared.activate(with: session.arSession)
        
        self.session = session
        
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
    }
    
    func stopSession() {
        mockTimer?.invalidate()
        mockTimer = nil
        
        session?.stop()
        session = nil
        
        // Disable ARDepthPipeline and restore throttleInterval
        ARDepthPipeline.shared.deactivate()
        ARDepthPipeline.shared.throttleInterval = 0.20
    }
    
    private func startMockSession() {
        mockDistance = 3.0
        mockTimer?.invalidate()
        mockTimer = Timer.scheduledTimer(withTimeInterval: announcementInterval, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            
            // Simulate walking towards a door, then finding a chair
            self.mockDistance -= 0.4
            if self.mockDistance < 0.5 {
                self.mockDistance = 3.0
            }
            
            let result: String
            let status = "กำลังสแกนหาวัตถุ..."
            
            if self.mockDistance > 1.5 {
                result = String(format: "พบประตู ห่าง %.1f เมตร สถานะ เปิดอยู่ (จำลอง)", self.mockDistance)
            } else {
                result = String(format: "พบเก้าอี้ ห่าง %.1f เมตร สถานะ เก้าอี้ว่าง (จำลอง)", self.mockDistance)
            }
            
            AudioPipeline.shared.speak(result)
            HapticManager.shared.vibrateGeneralInfo()
            
            let userInfo: [AnyHashable: Any] = [
                "aiResult": result,
                "status": status
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
        }
        
        // Immediately post initial scanning message
        let userInfo: [AnyHashable: Any] = [
            "aiResult": "กำลังจำลองการสแกนหาประตูและเก้าอี้...",
            "status": "กำลังเริ่มสแกน..."
        ]
        NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
    }
    
    func pauseSession() {
        session?.stop()
        ARDepthPipeline.shared.deactivate()
        LogStore.shared.log("[RoomPlan] Session paused (GPU yield).")
    }
    
    func resumeSession() {
        guard let session = self.session else { return }
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
        
        // Restore delegate and active state
        ARDepthPipeline.shared.activate(with: session.arSession)
        LogStore.shared.log("[RoomPlan] Session resumed.")
    }
    
    func roomCaptureSession(_ session: RoomCaptureSession, didUpdate room: CapturedRoom) {
        // Get current camera position relative to session origin
        var cameraPos = simd_make_float3(0, 0, 0)
        if let cameraTransform = session.arSession.currentFrame?.camera.transform {
            cameraPos = simd_make_float3(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)
        }
        
        var doorAnnouncements: [String] = []
        for door in room.doors {
            // Transform matrix columns.3 gives the 3D position relative to origin
            let doorPos = simd_make_float3(door.transform.columns.3.x, door.transform.columns.3.y, door.transform.columns.3.z)
            let distance = simd_distance(doorPos, cameraPos)
            
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
                let distance = simd_distance(objectPos, cameraPos)
                
                let isOccupied = checkOccupancy(for: object)
                let statusText = isOccupied ? "ไม่ว่าง" : "เก้าอี้ว่าง"
                
                let categoryName = object.category == .chair ? "เก้าอี้" : "โซฟา"
                furnitureAnnouncements.append(String(format: "พบ%@ ห่าง %.1f เมตร สถานะ %@", categoryName, distance, statusText))
            }
        }
        
        // Find first announcement for UI presentation
        let uiAnnouncement: String
        if let firstDoor = doorAnnouncements.first {
            uiAnnouncement = firstDoor
        } else if let firstFurniture = furnitureAnnouncements.first {
            uiAnnouncement = firstFurniture
        } else {
            uiAnnouncement = ""
        }
        
        if !uiAnnouncement.isEmpty {
            let userInfo: [AnyHashable: Any] = [
                "aiResult": uiAnnouncement,
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
        
        // Throttled speech and haptics output (only once per 4 seconds)
        let now = CACurrentMediaTime()
        if now - lastAnnouncedTime >= announcementInterval, !uiAnnouncement.isEmpty {
            lastAnnouncedTime = now
            AudioPipeline.shared.speak(uiAnnouncement)
            HapticManager.shared.vibrateGeneralInfo()
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
