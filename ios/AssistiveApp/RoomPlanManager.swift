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
    
    private var delegateProxy: ARSessionDelegateProxy?
    private var delegateObserverContext = 0
    private var isSettingDelegate = false
    private var isObservingDelegate = false
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
        
        // Register KVO on delegate to prevent RoomCaptureSession from overwriting the proxy
        if !isObservingDelegate {
            session.arSession.addObserver(self, forKeyPath: "delegate", options: [.initial, .new], context: &delegateObserverContext)
            isObservingDelegate = true
            LogStore.shared.log("[RoomPlan] Registered delegate KVO observer.")
        }
        
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
    }
    
    func stopSession() {
        mockTimer?.invalidate()
        mockTimer = nil
        
        if let session = session {
            if isObservingDelegate {
                session.arSession.removeObserver(self, forKeyPath: "delegate", context: &delegateObserverContext)
                isObservingDelegate = false
                LogStore.shared.log("[RoomPlan] Unregistered delegate KVO observer (stop).")
            }
            session.stop()
        }
        
        session = nil
        delegateProxy = nil // Release proxy to avoid retain cycles/leaks
        
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
        if let session = session {
            if isObservingDelegate {
                session.arSession.removeObserver(self, forKeyPath: "delegate", context: &delegateObserverContext)
                isObservingDelegate = false
                LogStore.shared.log("[RoomPlan] Unregistered delegate KVO observer (pause).")
            }
            session.stop()
        }
        delegateProxy = nil // Release proxy
        ARDepthPipeline.shared.deactivate()
        LogStore.shared.log("[RoomPlan] Session paused (GPU yield).")
    }
    
    func resumeSession() {
        guard let session = self.session else { return }
        
        // Ensure KVO is registered
        if !isObservingDelegate {
            session.arSession.addObserver(self, forKeyPath: "delegate", options: [.initial, .new], context: &delegateObserverContext)
            isObservingDelegate = true
            LogStore.shared.log("[RoomPlan] Registered delegate KVO observer (resume).")
        }
        
        let config = RoomCaptureSession.Configuration()
        session.run(configuration: config)
        
        ARDepthPipeline.shared.activate(with: session.arSession)
        LogStore.shared.log("[RoomPlan] Session resumed.")
    }
    
    override func observeValue(forKeyPath keyPath: String?, of object: Any?, change: [NSKeyValueChangeKey : Any]?, context: UnsafeMutableRawPointer?) {
        if context == &delegateObserverContext {
            guard let arSession = object as? ARSession else { return }
            guard !isSettingDelegate else { return }
            
            let newDelegateValue = arSession.delegate
            if let newDelegate = newDelegateValue {
                if newDelegate is ARSessionDelegateProxy {
                    return
                }
                
                LogStore.shared.log("[RoomPlan] Intercepted new ARSession delegate: \(type(of: newDelegate))")
                isSettingDelegate = true
                let proxy = ARSessionDelegateProxy(original: newDelegate, secondary: ARDepthPipeline.shared)
                self.delegateProxy = proxy
                arSession.delegate = proxy
                isSettingDelegate = false
            } else {
                LogStore.shared.log("[RoomPlan] Intercepted nil ARSession delegate")
                isSettingDelegate = true
                let proxy = ARSessionDelegateProxy(original: nil, secondary: ARDepthPipeline.shared)
                self.delegateProxy = proxy
                arSession.delegate = proxy
                isSettingDelegate = false
            }
        } else {
            super.observeValue(forKeyPath: keyPath, of: object, change: change, context: context)
        }
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
            let objectPos = simd_make_float3(object.transform.columns.3.x, object.transform.columns.3.y, object.transform.columns.3.z)
            let distance = simd_distance(objectPos, cameraPos)
            
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
            
            if !categoryName.isEmpty {
                let announcement: String
                if !statusText.isEmpty {
                    announcement = String(format: "พบ%@ ห่าง %.1f เมตร สถานะ %@", categoryName, distance, statusText)
                } else {
                    announcement = String(format: "พบ%@ ห่าง %.1f เมตร", categoryName, distance)
                }
                furnitureAnnouncements.append(announcement)
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

// Proxy delegate to broadcast ARSession delegate calls to both RoomPlan's internal listener and ARDepthPipeline
@available(iOS 16.0, *)
class ARSessionDelegateProxy: NSObject, ARSessionDelegate {
    private weak var originalDelegate: ARSessionDelegate?
    private weak var secondaryDelegate: ARSessionDelegate?
    
    init(original: ARSessionDelegate?, secondary: ARSessionDelegate?) {
        self.originalDelegate = original
        self.secondaryDelegate = secondary
        super.init()
    }
    
    override func responds(to aSelector: Selector!) -> Bool {
        if super.responds(to: aSelector) {
            return true
        }
        return (originalDelegate?.responds(to: aSelector) ?? false) || (secondaryDelegate?.responds(to: aSelector) ?? false)
    }
    
    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        originalDelegate?.session?(session, didUpdate: frame)
        secondaryDelegate?.session?(session, didUpdate: frame)
    }
    
    func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
        originalDelegate?.session?(session, didAdd: anchors)
        secondaryDelegate?.session?(session, didAdd: anchors)
    }
    
    func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
        originalDelegate?.session?(session, didUpdate: anchors)
        secondaryDelegate?.session?(session, didUpdate: anchors)
    }
    
    func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
        originalDelegate?.session?(session, didRemove: anchors)
        secondaryDelegate?.session?(session, didRemove: anchors)
    }
    
    func session(_ session: ARSession, didFailWithError error: Error) {
        originalDelegate?.session?(session, didFailWithError: error)
        secondaryDelegate?.session?(session, didFailWithError: error)
    }
    
    func session(_ session: ARSession, cameraDidChangeTrackingState camera: ARCamera) {
        originalDelegate?.session?(session, cameraDidChangeTrackingState: camera)
        secondaryDelegate?.session?(session, cameraDidChangeTrackingState: camera)
    }
    
    func sessionWasInterrupted(_ session: ARSession) {
        originalDelegate?.sessionWasInterrupted?(session)
        secondaryDelegate?.sessionWasInterrupted?(session)
    }
    
    func sessionInterruptionEnded(_ session: ARSession) {
        originalDelegate?.sessionInterruptionEnded?(session)
        secondaryDelegate?.sessionInterruptionEnded?(session)
    }
    
    func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool {
        let orig = originalDelegate?.sessionShouldAttemptRelocalization?(session) ?? false
        let sec = secondaryDelegate?.sessionShouldAttemptRelocalization?(session) ?? false
        return orig || sec
    }
    
    func session(_ session: ARSession, didOutputCollaborationData data: ARSession.CollaborationData) {
        originalDelegate?.session?(session, didOutputCollaborationData: data)
        secondaryDelegate?.session?(session, didOutputCollaborationData: data)
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
