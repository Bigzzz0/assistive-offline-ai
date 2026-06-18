import ARKit
import Foundation

#if canImport(RoomPlan)
import RoomPlan

struct AnnouncedObjectState {
    let category: String
    let distanceZone: Int
    let statusText: String
    let timestamp: Double
}

@available(iOS 16.0, *)
class RoomPlanManager: NSObject, RoomCaptureSessionDelegate {
    static let shared = RoomPlanManager()
    
    private(set) var session: RoomCaptureSession?
    
    private var framePollTimer: Timer?
    private var mockTimer: Timer?
    private var mockDistance: Float = 3.0
    
    private var announcedStates: [UUID: AnnouncedObjectState] = [:]
    private var lastSpeechTime: Double = 0.0
    private let speechInterval: Double = 3.0 // Minimum 3 seconds between announcements
    
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
        
        // Poll for currentFrame directly without touching delegate
        framePollTimer?.invalidate()
        framePollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self,
                  let session = self.session,
                  let frame = session.arSession.currentFrame else { return }
            
            ARDepthPipeline.shared.processManualFrame(frame)
        }
    }
    
    private let announcementInterval: Double = 4.0 // For simulated session
    
    func stopSession() {
        mockTimer?.invalidate()
        mockTimer = nil
        framePollTimer?.invalidate()
        framePollTimer = nil
        
        session?.stop()
        session = nil
        
        announcedStates.removeAll()
        lastSpeechTime = 0.0
        
        // Disable ARDepthPipeline and restore throttleInterval
        ARDepthPipeline.shared.deactivate()
        ARDepthPipeline.shared.throttleInterval = 0.20
        
        // Stop any active tones
        AudioPipeline.shared.updateDistanceAlert(distance: 999.0)
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
        framePollTimer?.invalidate()
        framePollTimer = nil
        session?.stop()
        announcedStates.removeAll()
        lastSpeechTime = 0.0
        ARDepthPipeline.shared.deactivate()
        AudioPipeline.shared.updateDistanceAlert(distance: 999.0)
        LogStore.shared.log("[RoomPlan] Session paused (GPU yield).")
    }
    
    func resumeSession() {
        guard let session = self.session else { return }
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
        
        ARDepthPipeline.shared.activate(with: session.arSession)
        
        // Poll for currentFrame directly without touching delegate
        framePollTimer?.invalidate()
        framePollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self,
                  let session = self.session,
                  let frame = session.arSession.currentFrame else { return }
            
            ARDepthPipeline.shared.processManualFrame(frame)
        }
        
        LogStore.shared.log("[RoomPlan] Session resumed.")
    }
    
    func captureSession(_ session: RoomCaptureSession, didUpdate room: CapturedRoom) {
        // Get current camera position relative to session origin
        var cameraPos = simd_make_float3(0, 0, 0)
        if let cameraTransform = session.arSession.currentFrame?.camera.transform {
            cameraPos = simd_make_float3(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)
        }
        
        var minDistance: Float = 999.0
        var closestObstacleLocalPos: simd_float3? = nil
        let now = ProcessInfo.processInfo.systemUptime
        var announcementsToMake: [(text: String, distance: Float, id: UUID, state: AnnouncedObjectState)] = []
        
        // 1. Process Doors
        for door in room.doors {
            let doorPos = simd_make_float3(door.transform.columns.3.x, door.transform.columns.3.y, door.transform.columns.3.z)
            let distance = simd_distance(doorPos, cameraPos)
            
            if distance < minDistance {
                minDistance = distance
                if let cameraTransform = session.arSession.currentFrame?.camera.transform {
                    let localPos4 = cameraTransform.inverse * simd_make_float4(doorPos.x, doorPos.y, doorPos.z, 1.0)
                    closestObstacleLocalPos = simd_make_float3(localPos4.x, localPos4.y, localPos4.z)
                }
            }
            
            var isOpenText = "ปิดอยู่"
            if case let .door(isOpen) = door.category {
                isOpenText = isOpen ? "เปิดอยู่" : "ปิดอยู่"
            } else {
                let angle = atan2(door.transform.columns.0.z, door.transform.columns.0.x)
                isOpenText = abs(angle) > 0.1 ? "เปิดอยู่" : "ปิดอยู่"
            }
            
            let currentZone: Int
            if distance < 1.5 {
                currentZone = 1
            } else if distance < 3.0 {
                currentZone = 2
            } else {
                currentZone = 3
            }
            
            let stateKey = door.identifier
            let lastState = announcedStates[stateKey]
            
            var shouldAnnounce = false
            if let last = lastState {
                if last.distanceZone != currentZone {
                    shouldAnnounce = true
                } else if last.statusText != isOpenText {
                    shouldAnnounce = true
                } else if currentZone == 1 && (now - last.timestamp > 15.0) {
                    shouldAnnounce = true // Periodic reminder for very close items
                }
            } else {
                shouldAnnounce = true
            }
            
            let newState = AnnouncedObjectState(
                category: "ประตู",
                distanceZone: currentZone,
                statusText: isOpenText,
                timestamp: shouldAnnounce ? now : (lastState?.timestamp ?? now)
            )
            
            if shouldAnnounce {
                let announcement = String(format: "พบประตู ห่าง %.1f เมตร สถานะ %@", distance, isOpenText)
                announcementsToMake.append((text: announcement, distance: distance, id: door.identifier, state: newState))
            } else {
                announcedStates[door.identifier] = newState
            }
        }
        
        // 2. Process Furniture Objects
        for object in room.objects {
            let objectPos = simd_make_float3(object.transform.columns.3.x, object.transform.columns.3.y, object.transform.columns.3.z)
            let distance = simd_distance(objectPos, cameraPos)
            
            if distance < minDistance {
                minDistance = distance
                if let cameraTransform = session.arSession.currentFrame?.camera.transform {
                    let localPos4 = cameraTransform.inverse * simd_make_float4(objectPos.x, objectPos.y, objectPos.z, 1.0)
                    closestObstacleLocalPos = simd_make_float3(localPos4.x, localPos4.y, localPos4.z)
                }
            }
            
            var categoryName = ""
            var statusText = ""
            
            switch object.category {
            case .chair:
                categoryName = "เก้าอี้"
                let isOccupied = checkOccupancy(for: object)
                statusText = isOccupied ? "ไม่ว่าง" : "เก้าอี้ว่าง"
            case .sofa:
                categoryName = "โซฟา"
                let isOccupied = checkOccupancy(for: object)
                statusText = isOccupied ? "ไม่ว่าง" : "โซฟานั่งได้"
            case .bed:
                categoryName = "เตียง"
            case .table:
                categoryName = "โต๊ะ"
            case .storage:
                categoryName = "ตู้"
            case .toilet:
                categoryName = "ชักโครก"
            case .sink:
                categoryName = "อ่างล้างมือ"
            case .refrigerator:
                categoryName = "ตู้เย็น"
            default:
                continue
            }
            
            guard !categoryName.isEmpty else { continue }
            
            let currentZone: Int
            if distance < 1.5 {
                currentZone = 1
            } else if distance < 3.0 {
                currentZone = 2
            } else {
                currentZone = 3
            }
            
            let stateKey = object.identifier
            let lastState = announcedStates[stateKey]
            
            var shouldAnnounce = false
            if let last = lastState {
                if last.distanceZone != currentZone {
                    shouldAnnounce = true
                } else if last.statusText != statusText {
                    shouldAnnounce = true
                } else if currentZone == 1 && (now - last.timestamp > 15.0) {
                    shouldAnnounce = true
                }
            } else {
                shouldAnnounce = true
            }
            
            let newState = AnnouncedObjectState(
                category: categoryName,
                distanceZone: currentZone,
                statusText: statusText,
                timestamp: shouldAnnounce ? now : (lastState?.timestamp ?? now)
            )
            
            if shouldAnnounce {
                let announcement: String
                if !statusText.isEmpty {
                    announcement = String(format: "พบ%@ ห่าง %.1f เมตร สถานะ %@", categoryName, distance, statusText)
                } else {
                    announcement = String(format: "พบ%@ ห่าง %.1f เมตร", categoryName, distance)
                }
                announcementsToMake.append((text: announcement, distance: distance, id: object.identifier, state: newState))
            } else {
                announcedStates[object.identifier] = newState
            }
        }
        
        // 3. Keep Clean: Remove stale objects that are no longer in the room
        let currentIdentifiers = Set(room.doors.map { $0.identifier } + room.objects.map { $0.identifier })
        for id in announcedStates.keys {
            if !currentIdentifiers.contains(id) {
                announcedStates.removeValue(forKey: id)
            }
        }
        
        // 4. Update the real-time distance warning tone/haptics for the closest obstacle
        AudioPipeline.shared.updateDistanceAlert(distance: minDistance, position: closestObstacleLocalPos)
        
        // 5. Trigger spoken announcements if any, sorted by proximity (closest first)
        if !announcementsToMake.isEmpty {
            let sorted = announcementsToMake.sorted(by: { $0.distance < $1.distance })
            if let primary = sorted.first {
                if now - lastSpeechTime >= speechInterval {
                    lastSpeechTime = now
                    
                    // Actually speak
                    AudioPipeline.shared.speak(primary.text)
                    HapticManager.shared.vibrateGeneralInfo()
                    
                    // Save announced state only after speaking
                    announcedStates[primary.id] = primary.state
                    
                    // Post notification for UI
                    let userInfo: [AnyHashable: Any] = [
                        "aiResult": primary.text,
                        "status": "พบวัตถุเป้าหมาย"
                    ]
                    NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
                }
            }
        } else {
            // Keep UI updated but silent if there is nothing new to announce
            let uiText: String
            if minDistance < 999.0 {
                uiText = String(format: "สแกนพบห้อง มีวัตถุใกล้สุด %.1f เมตร", minDistance)
            } else {
                uiText = "ยังไม่พบประตูหรือเก้าอี้ใกล้ตัว"
            }
            let userInfo: [AnyHashable: Any] = [
                "aiResult": uiText,
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
