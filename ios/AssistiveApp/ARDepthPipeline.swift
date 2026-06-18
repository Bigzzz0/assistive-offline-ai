import ARKit
import Vision
import Foundation

class ARDepthPipeline: NSObject, ARSessionDelegate {
    static let shared = ARDepthPipeline()
    
    private(set) var session: ARSession?
    private var isProcessing = false
    private var lastProcessedTime: Double = 0.0
    private(set) var isActive = false
    private var savedConfig: ARWorldTrackingConfiguration?
    
    // Adaptive throttle: lower FPS when running alongside RoomPlan
    var throttleInterval: Double = 0.20 // Default 5 FPS
    
    // Store 3D coordinates of detected people (for Seat Occupancy in RoomPlan)
    private(set) var lastDetectedPeopleWorldPositions: [simd_float3] = []
    
    func startSession() {
        LogStore.shared.log("[ARDepthPipeline] Starting ARSession...")
        let session = ARSession()
        session.delegate = self
        self.session = session
        
        let config = ARWorldTrackingConfiguration()
        if ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth) {
            config.frameSemantics.insert(.sceneDepth)
            LogStore.shared.log("[ARDepthPipeline] LiDAR Scene Depth is supported and enabled.")
        } else {
            LogStore.shared.log("[ARDepthPipeline] LiDAR Scene Depth not supported. Using bounding-box distance fallback.")
        }
        
        self.savedConfig = config
        self.isActive = true
        session.run(config)
    }
    
    func stopSession() {
        session?.pause()
        session = nil
        isActive = false
        savedConfig = nil
        lastDetectedPeopleWorldPositions.removeAll()
    }
    
    func pauseSession() {
        guard isActive, let session = session else { return }
        session.pause()
        isActive = false
        LogStore.shared.log("[ARDepthPipeline] Session paused (GPU yield).")
    }
    
    func resumeSession() {
        guard !isActive, let session = session, let config = savedConfig else { return }
        session.run(config)
        isActive = true
        LogStore.shared.log("[ARDepthPipeline] Session resumed.")
    }
    
    func activate(with arSession: ARSession) {
        self.isActive = true
        // Note: When shared, delegate assignments are handled externally via proxy in RoomPlanManager.
        LogStore.shared.log("[ARDepthPipeline] Activated with shared ARSession.")
    }
    
    func deactivate() {
        self.isActive = false
        lastDetectedPeopleWorldPositions.removeAll()
        LogStore.shared.log("[ARDepthPipeline] Deactivated.")
    }
    
    private func getCGImageOrientation() -> CGImagePropertyOrientation {
        let deviceOrientation = UIDevice.current.orientation
        switch deviceOrientation {
        case .portrait:
            return .right
        case .portraitUpsideDown:
            return .left
        case .landscapeLeft:
            return .up
        case .landscapeRight:
            return .down
        default:
            return .right // Default fallback for portrait
        }
    }
    
    private func mapToRawImageCoordinates(point: CGPoint, orientation: CGImagePropertyOrientation) -> CGPoint {
        switch orientation {
        case .up:
            return CGPoint(x: point.x, y: point.y)
        case .down:
            return CGPoint(x: 1.0 - point.x, y: 1.0 - point.y)
        case .right:
            return CGPoint(x: 1.0 - point.y, y: point.x)
        case .left:
            return CGPoint(x: point.y, y: 1.0 - point.x)
        default:
            return CGPoint(x: 1.0 - point.y, y: point.x) // Default for portrait (.right)
        }
    }

    func processManualFrame(_ frame: ARFrame) {
        processFrame(frame)
    }
    
    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        processFrame(frame)
    }
    
    private func processFrame(_ frame: ARFrame) {
        guard isActive else { return }
        let now = CACurrentMediaTime()
        // Throttle to adaptive interval to save GPU/CPU thermals
        guard now - lastProcessedTime >= throttleInterval else { return }
        guard !isProcessing else { return }
        
        isProcessing = true
        lastProcessedTime = now
        
        let pixelBuffer = frame.capturedImage
        
        let personRequest = VNDetectHumanRectanglesRequest()
        let orientation = getCGImageOrientation()
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try handler.perform([personRequest])
                self.processPersonObservations(frame: frame, observations: personRequest.results ?? [], orientation: orientation)
            } catch {
                print("AR Vision error: \(error.localizedDescription)")
            }
            self.isProcessing = false
        }
    }
    
    private func processPersonObservations(frame: ARFrame, observations: [VNHumanObservation], orientation: CGImagePropertyOrientation) {
        guard !observations.isEmpty else {
            lastDetectedPeopleWorldPositions.removeAll()
            AudioPipeline.shared.updateDistanceAlert(distance: 999.0) // Safe
            
            let userInfo: [AnyHashable: Any] = [
                "aiResult": "ไม่พบคนในระยะสแกน",
                "status": "ไม่พบคนรอบตัว"
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
            return
        }
        
        var minDistance: Float = 999.0
        var currentPositions: [simd_float3] = []
        
        for observation in observations {
            let bbox = observation.boundingBox // Normalized [0, 1] bottom-left origin in oriented space
            let center = CGPoint(x: bbox.midX, y: bbox.midY)
            
            // Map oriented center to raw image coordinates
            let rawCenter = mapToRawImageCoordinates(point: center, orientation: orientation)
            
            var distance: Float = 0.0
            var hasLiDARDepth = false
            
            if let sceneDepth = frame.sceneDepth {
                let depthMap = sceneDepth.depthMap
                CVPixelBufferLockBaseAddress(depthMap, .readOnly)
                let width = CVPixelBufferGetWidth(depthMap)
                let height = CVPixelBufferGetHeight(depthMap)
                
                // Convert raw coordinate (origin bottom-left) to Depth map (origin top-left)
                let xIndex = Int(rawCenter.x * CGFloat(width - 1))
                let yIndex = Int((1.0 - rawCenter.y) * CGFloat(height - 1))
                
                if xIndex >= 0 && xIndex < width && yIndex >= 0 && yIndex < height {
                    let baseAddress = CVPixelBufferGetBaseAddress(depthMap)
                    let bytesPerRow = CVPixelBufferGetBytesPerRow(depthMap)
                    let rowData = baseAddress?.advanced(by: yIndex * bytesPerRow)
                    let depthPointer = rowData?.assumingMemoryBound(to: Float32.self)
                    let depthVal = depthPointer?[xIndex] ?? 0.0
                    if depthVal > 0.1 {
                        distance = depthVal
                        hasLiDARDepth = true
                    }
                }
                CVPixelBufferUnlockBaseAddress(depthMap, .readOnly)
            }
            
            if !hasLiDARDepth {
                // Fallback: estimate distance from bounding box height (average human height ~1.7m)
                // If bbox.height is 1.0 (fills screen height), person is ~1.5 meters away
                distance = 1.5 / Float(bbox.height)
            }
            
            if distance < minDistance {
                minDistance = distance
            }
            
            // Project 2D raw center coordinate and depth into 3D world space
            let projected3D = projectToWorld(rawCenter: rawCenter, depth: distance, frame: frame)
            currentPositions.append(projected3D)
        }
        
        self.lastDetectedPeopleWorldPositions = currentPositions
        AudioPipeline.shared.updateDistanceAlert(distance: minDistance)
        
        let status: String
        let result: String
        if minDistance < 999.0 {
            status = String(format: "พบคนใกล้ที่สุด %.2f เมตร", minDistance)
            if minDistance < 1.5 {
                result = String(format: "⚠️ เตือน! มีคนอยู่ใกล้เกินไป (%.1f ม.) กรุณาเว้นระยะห่าง", minDistance)
            } else {
                result = String(format: "พบคนในระยะปลอดภัย (%.1f ม.)", minDistance)
            }
        } else {
            status = "ไม่พบคนรอบตัว"
            result = "ไม่พบคนในระยะสแกน"
        }
        
        let userInfo: [AnyHashable: Any] = [
            "aiResult": result,
            "status": status
        ]
        NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
    }
    
    private func projectToWorld(rawCenter: CGPoint, depth: Float, frame: ARFrame) -> simd_float3 {
        let camera = frame.camera
        let imageSize = camera.imageResolution
        
        // Normalized raw point to pixel coordinates
        let x = Float(rawCenter.x) * Float(imageSize.width)
        let y = Float(1.0 - rawCenter.y) * Float(imageSize.height) // convert bottom-left to top-left for camera unprojection
        
        let intrinsics = camera.intrinsics
        let fx = intrinsics[0][0]
        let fy = intrinsics[1][1]
        let ox = intrinsics[2][0]
        let oy = intrinsics[2][1]
        
        let z = depth
        let x3d = (x - ox) * z / fx
        let y3d = (y - oy) * z / fy
        
        let pointInCamera = simd_make_float3(x3d, y3d, z)
        let pointInWorld = camera.transform * simd_make_float4(pointInCamera, 1.0)
        
        return simd_make_float3(pointInWorld.x, pointInWorld.y, pointInWorld.z)
    }
}
