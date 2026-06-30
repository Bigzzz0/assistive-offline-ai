import AVFoundation
import UIKit
import CoreVideo
import CoreMedia
import Vision
import AudioToolbox

class VisionPipeline: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    static let shared = VisionPipeline()
    
    var isProcessing = false
    
    // Public session for CameraPreviewView binding
    private(set) var session: AVCaptureSession?
    
    private var lastProcessedTime: Double = 0.0
    private let frameInterval: Double = 0.2 // 5 FPS (200ms)
    
    // Frame capture request state
    private var pendingFrameCapture: ((Data?) -> Void)?
    private let captureLock = NSLock()
    
    var onFrameCaptured: ((Data) -> Void)?
    var isFrameRequested: (() -> Bool)?
    
    // Point and Speak States
    var activeMode: ActiveMode = .object
    private var lastAnalysisTime: Double = 0.0
    private var lastSpokenText: String = ""
    private var lastSpokenTime: Double = 0.0
    private var lastBeepTime: Double = 0.0
    
    // Document Auto-Capture States
    private var stableStartTime: Double?
    private var lastGuidanceSpokenTime: Double = 0.0
    
    func startSession() {
        let captureSession = AVCaptureSession()
        captureSession.sessionPreset = .vga640x480
        
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera) else {
            return
        }
        
        if captureSession.canAddInput(input) {
            captureSession.addInput(input)
        }
        
        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "camera_queue"))
        if captureSession.canAddOutput(output) {
            captureSession.addOutput(output)
            if let connection = output.connection(with: .video) {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = .portrait
                }
            }
        }
        
        self.session = captureSession
        DispatchQueue.global(qos: .userInitiated).async {
            captureSession.startRunning()
        }
    }
    
    func stopSession() {
        session?.stopRunning()
    }
    
    /// Thread-safe one-shot frame capture. Grabs the next available frame as JPEG data.
    func captureCurrentFrame(completion: @escaping (Data?) -> Void) {
        captureLock.lock()
        pendingFrameCapture = completion
        captureLock.unlock()
    }
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        
        // Check for pending one-shot capture request
        captureLock.lock()
        let pending = pendingFrameCapture
        pendingFrameCapture = nil
        captureLock.unlock()
        
        if let pending = pending {
            let jpegData = convertToJpeg(pixelBuffer: pixelBuffer)
            DispatchQueue.main.async {
                pending(jpegData)
            }
            lastProcessedTime = now
            return
        }
        
        // ── Point and Speak Mode ──
        if activeMode == .pointAndSpeak {
            if now - lastAnalysisTime >= 0.25 { // 4 FPS for battery efficiency
                lastAnalysisTime = now
                runPointAndSpeak(pixelBuffer: pixelBuffer, now: now)
            }
            return
        }
        
        // ── Document Auto-Capture Mode (OCR) ──
        if activeMode == .read {
            if now - lastAnalysisTime >= 0.20 { // 5 FPS
                lastAnalysisTime = now
                runDocumentAutoCapture(pixelBuffer: pixelBuffer, now: now)
            }
            return
        }
        
        // Legacy continuous callback path
        let requested = isFrameRequested?() ?? false
        
        // Throttling: discard frames if idle and interval not elapsed
        if !requested {
            if now - lastProcessedTime < frameInterval {
                return
            }
        }
        lastProcessedTime = now
        
        if requested {
            if let jpegData = convertToJpeg(pixelBuffer: pixelBuffer) {
                onFrameCaptured?(jpegData)
            }
        }
    }
    
    private func runPointAndSpeak(pixelBuffer: CVPixelBuffer, now: Double) {
        let handPoseRequest = VNDetectHumanHandPoseRequest()
        handPoseRequest.maximumHandCount = 1
        
        let textRequest = VNRecognizeTextRequest()
        textRequest.recognitionLanguages = ["th-TH", "en-US"]
        textRequest.recognitionLevel = .accurate
        textRequest.usesLanguageCorrection = true
        
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        
        do {
            try handler.perform([handPoseRequest, textRequest])
        } catch {
            print("Vision performance error: \(error.localizedDescription)")
            return
        }
        
        // 1. Extract Index Fingertip
        guard let handObservation = handPoseRequest.results?.first else {
            let userInfo: [AnyHashable: Any] = [
                "aiResult": "",
                "status": "ไม่พบมือหรือนิ้วชี้"
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
            return // No hand detected
        }
        
        guard let indexTipJoint = try? handObservation.recognizedPoint(.indexTip),
              indexTipJoint.confidence > 0.3 else {
            let userInfo: [AnyHashable: Any] = [
                "aiResult": "",
                "status": "นิ้วชี้ไม่ชัดเจน"
            ]
            NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
            return // Index tip not confident
        }
        
        let fingerTipPoint = indexTipJoint.location // Normalized bottom-left coordinate space
        
        // 2. Extract Text and check proximity/intersection
        guard let textObservations = textRequest.results else { return }
        
        var minDistance: CGFloat = 1.0
        var detectedText: String? = nil
        
        for observation in textObservations {
            // Expand bounding box slightly for easier UX (2% horizontal/vertical padding)
            let box = observation.boundingBox
            let expandedBox = box.insetBy(dx: -0.02, dy: -0.02)
            
            // Calculate distance to text box
            let dist = distance(from: fingerTipPoint, to: box)
            if dist < minDistance {
                minDistance = dist
            }
            
            // Check containment
            if expandedBox.contains(fingerTipPoint) {
                if let candidate = observation.topCandidates(1).first {
                    detectedText = candidate.string
                }
            }
        }
        
        // 3. Play distance beep feedback
        let beepInterval: Double
        if minDistance < 0.03 {
            beepInterval = 0.15 // Very close/touching: rapid ticks
        } else if minDistance < 0.08 {
            beepInterval = 0.40 // Medium distance
        } else if minDistance < 0.15 {
            beepInterval = 0.80 // Farther
        } else {
            beepInterval = 0.0  // Out of range: no sound
        }
        
        if beepInterval > 0.0 && (now - lastBeepTime >= beepInterval) {
            lastBeepTime = now
            if !AudioPipeline.shared.isBeepAlertMuted {
                // System sound ID 1104 is a gentle Tink sound
                AudioServicesPlaySystemSound(1104)
            }
        }
        
        // Post notification about finger distance and hovered text
        let status = String(format: "นิ้วชี้ห่างจากข้อความใกล้สุด %.2f", minDistance)
        let userInfo: [AnyHashable: Any] = [
            "aiResult": detectedText != nil ? "👉 ชี้แล้วอ่าน: \(detectedText!)" : "ยื่นนิ้วชี้ไปเพื่ออ่านตัวหนังสือ",
            "status": status
        ]
        NotificationCenter.default.post(name: NSNotification.Name("AccessibilityPipelineDidUpdate"), object: nil, userInfo: userInfo)
        
        // 4. Speak text if hovering over it
        if let text = detectedText, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if text != lastSpokenText || (now - lastSpokenTime >= 2.0) {
                lastSpokenText = text
                lastSpokenTime = now
                
                // Trigger tactile feedback (medium impact)
                HapticManager.shared.vibrateGeneralInfo()
                
                // Speak the text
                AudioPipeline.shared.speak(text)
            }
        }
    }
    
    private func distance(from point: CGPoint, to rect: CGRect) -> CGFloat {
        let dx = max(rect.minX - point.x, 0, point.x - rect.maxX)
        let dy = max(rect.minY - point.y, 0, point.y - rect.maxY)
        return sqrt(dx*dx + dy*dy)
    }
    
    func convertToJpeg(pixelBuffer: CVPixelBuffer) -> Data? {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        let uiImage = UIImage(cgImage: cgImage)
        return uiImage.jpegData(compressionQuality: 0.5) // Quality 50 optimization
    }
    
    private func runDocumentAutoCapture(pixelBuffer: CVPixelBuffer, now: Double) {
        guard !isProcessing else {
            stableStartTime = nil
            return
        }
        let request = VNDetectRectanglesRequest()
        request.minimumConfidence = 0.5
        request.minimumAspectRatio = 0.5
        request.maximumObservations = 1
        
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up, options: [:])
        do {
            try handler.perform([request])
        } catch {
            print("Vision rectangle detection error: \(error.localizedDescription)")
            return
        }
        
        guard let observation = request.results?.first else {
            stableStartTime = nil
            return
        }
        
        let box = observation.boundingBox
        let area = box.width * box.height
        let boxCenter = box.origin.x + box.width / 2.0
        
        // Target criteria: centered horizontal (0.4 - 0.6) and size >= 40% of viewport
        let isCentered = boxCenter >= 0.4 && boxCenter <= 0.6
        let isLargeEnough = area >= 0.40
        
        if isCentered && isLargeEnough {
            if stableStartTime == nil {
                stableStartTime = now
            } else if now - stableStartTime! >= 1.0 {
                // Stable for 1.0 second! Auto-capture!
                stableStartTime = nil // Reset stable state
                
                DispatchQueue.main.async {
                    // Trigger haptic and shutter notification
                    HapticManager.shared.vibrateSuccess()
                    AudioServicesPlaySystemSound(1108) // Shutter Sound
                    
                    NotificationCenter.default.post(
                        name: NSNotification.Name("TriggerOCRNotification"),
                        object: nil
                    )
                }
            }
        } else {
            stableStartTime = nil // Reset stable state
            
            // Speak guidance if cooldown elapsed
            if now - lastGuidanceSpokenTime >= 2.0 {
                lastGuidanceSpokenTime = now
                if !isCentered {
                    if boxCenter < 0.4 {
                        AudioPipeline.shared.speak("ขยับกล้องไปทางซ้าย")
                    } else {
                        AudioPipeline.shared.speak("ขยับกล้องไปทางขวา")
                    }
                } else if !isLargeEnough {
                    AudioPipeline.shared.speak("ขยับกล้องเข้ามาใกล้ขึ้น")
                }
            }
        }
    }
}
