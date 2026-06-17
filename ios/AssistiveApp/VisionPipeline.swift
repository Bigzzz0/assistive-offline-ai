import AVFoundation
import UIKit
import CoreVideo
import CoreMedia

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
    
    private func convertToJpeg(pixelBuffer: CVPixelBuffer) -> Data? {
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let context = CIContext()
        guard let cgImage = context.createCGImage(ciImage, from: ciImage.extent) else { return nil }
        let uiImage = UIImage(cgImage: cgImage)
        return uiImage.jpegData(compressionQuality: 0.5) // Quality 50 optimization
    }
}
