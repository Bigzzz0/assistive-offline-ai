import AVFoundation
import UIKit
import CoreVideo
import CoreMedia

class VisionPipeline: NSObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    static let shared = VisionPipeline()
    
    private var captureSession: AVCaptureSession?
    private var lastProcessedTime: Double = 0.0
    private let frameInterval: Double = 0.2 // 5 FPS (200ms)
    
    var onFrameCaptured: ((Data) -> Void)?
    var isFrameRequested: (() -> Bool)?
    
    func startSession() {
        let session = AVCaptureSession()
        session.sessionPreset = .vga640x480
        
        guard let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: camera) else {
            return
        }
        
        if session.canAddInput(input) {
            session.addInput(input)
        }
        
        let output = AVCaptureVideoDataOutput()
        output.setSampleBufferDelegate(self, queue: DispatchQueue(label: "camera_queue"))
        if session.canAddOutput(output) {
            session.addOutput(output)
        }
        
        self.captureSession = session
        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }
    
    func stopSession() {
        captureSession?.stopRunning()
    }
    
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        let now = CACurrentMediaTime()
        let requested = isFrameRequested?() ?? false
        
        // Throttling: discard frames if idle and interval not elapsed
        if !requested {
            if now - lastProcessedTime < frameInterval {
                return
            }
        }
        lastProcessedTime = now
        
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        
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
