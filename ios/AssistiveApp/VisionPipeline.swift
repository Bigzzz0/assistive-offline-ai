import AVFoundation
import UIKit
import CoreVideo
import CoreMedia
import Vision
import AudioToolbox

class VisionPipeline: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    static let shared = VisionPipeline()
    
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
            return // No hand detected
        }
        
        guard let indexTipJoint = try? handObservation.recognizedPoint(.indexTip),
              indexTipJoint.confidence > 0.3 else {
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
            // System sound ID 1104 is a gentle Tink sound
            AudioServicesPlaySystemSound(1104)
        }
        
        // 4. Speak text if hovering over it
        if let text = detectedText, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            if text != lastSpokenText || (now - lastSpokenTime >= 2.0) {
                lastSpokenText = text
                lastSpokenTime = now
                
                // Trigger tactile feedback (medium impact)
                DispatchQueue.main.async {
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.prepare()
                    generator.impactOccurred()
                }
                
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
    
    private func convertToJpeg(pixelBuffer: CVPixelBuffer) -> Data? {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        let uiImage = UIImage(cgImage: cgImage)
        return uiImage.jpegData(compressionQuality: 0.5) // Quality 50 optimization
    }
}
