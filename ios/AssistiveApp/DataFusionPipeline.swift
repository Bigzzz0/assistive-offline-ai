import Foundation
import ARKit
import Vision

/// คลาสสำหรับประสานข้อมูลภาพกล้อง (Vision) และแผนที่ระยะลึก (LiDAR Depth)
/// เพื่อนำมาแปลเป็นข้อมูลตำแหน่งเชิงพื้นที่ (Data Fusion) ป้อนให้โมเดลภาษาภาษาไทย Gemma
class DataFusionPipeline {
    static let shared = DataFusionPipeline()
    
    private var isProcessing = false
    
    // ใช้คำขอวิเคราะห์ส่วนของวัตถุเด่นในภาพ (Saliency/Objectness) ซึ่งมีใน iOS 13.0+ โดยไม่ต้องโหลด CoreML เพิ่มเติม
    private let saliencyRequest = VNGenerateObjectnessBasedImageSegmentationsRequest()
    
    /// ประมวลผลเฟรม AR เพื่อทำประมวลข้อมูล Fusion ของสิ่งกีดขวางและระยะลึก
    func processFrameForFusion(frame: ARFrame, completion: @escaping (String) -> Void) {
        guard !isProcessing else { return }
        isProcessing = true
        
        let pixelBuffer = frame.capturedImage
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .right, options: [:])
        
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            do {
                try handler.perform([self.saliencyRequest])
                let observations = self.saliencyRequest.results ?? []
                
                var detectedEntities: [String] = []
                
                // ตรวจหาวัตถุสูงสุด 3 ชิ้นเพื่อความรวดเร็วและไม่สับสน
                let targetObservations = observations.prefix(3)
                
                for observation in targetObservations {
                    // Vision boundingBox ใช้ระบบพิกัดด้านล่างซ้าย (0,0) ไปขวาบน (1,1)
                    let bbox = observation.boundingBox
                    let label = "วัตถุเบื้องหน้า"
                    
                    // คำนวณพิกัดกึ่งกลาง Bounding Box
                    let centerX = bbox.midX
                    let centerY = bbox.midY
                    
                    // ค้นหาระยะลึกจริงจากแผนที่ความลึก LiDAR
                    var depth: Float = 0.0
                    if let sceneDepth = frame.sceneDepth {
                        depth = self.getDepthAtPoint(pixelBuffer: sceneDepth.depthMap, normalizedX: centerX, normalizedY: centerY)
                    } else {
                        // หากไม่มี LiDAR ให้ประเมินจากขนาดของ Bounding Box (เฉลี่ยระยะ)
                        depth = 1.5 / Float(max(bbox.height, 0.1))
                    }
                    
                    // ข้ามกรณีตรวจจับผิดพลาดหรือระยะไกลเกินไป (เกิน 5 เมตร)
                    if depth < 0.1 || depth > 5.0 { continue }
                    
                    // แปลงพิกัดแกนนอน X เป็นคำบอกทิศทางตามหน้าปัดกล้อง
                    let direction: String
                    if centerX < 0.35 {
                        direction = "อยู่ทางซ้าย"
                    } else if centerX > 0.65 {
                        direction = "อยู่ทางขวา"
                    } else {
                        direction = "อยู่ตรงกลางข้างหน้า"
                    }
                    
                    let alertTag = depth < 1.2 ? "(อันตราย)" : "(แจ้งเตือน)"
                    detectedEntities.append(String(format: "พบ %@ ระยะ %.1f เมตร %@", label, depth, direction))
                }
                
                let rawText: String
                if detectedEntities.isEmpty {
                    // หากไม่พบวัตถุใหญ่กีดขวาง ให้เช็กระยะตรงหน้าตรงๆ
                    var centerDepth: Float = 0.0
                    if let sceneDepth = frame.sceneDepth {
                        centerDepth = self.getDepthAtPoint(pixelBuffer: sceneDepth.depthMap, normalizedX: 0.5, normalizedY: 0.5)
                    }
                    if centerDepth > 0.1 && centerDepth < 2.5 {
                        rawText = String(format: "สภาพแวดล้อมเบื้องหน้า: มีสิ่งกีดขวางระยะ %.1f เมตร ตรงหน้า", centerDepth)
                    } else {
                        rawText = "สภาพแวดล้อมเบื้องหน้า: ทางสะดวก ไม่มีสิ่งกีดขวางเด่นชัด"
                    }
                } else {
                    rawText = "สภาพแวดล้อมเบื้องหน้า: " + detectedEntities.joined(separator: ", ")
                }
                
                DispatchQueue.main.async {
                    self.isProcessing = false
                    completion(rawText)
                }
            } catch {
                DispatchQueue.main.async {
                    self.isProcessing = false
                    completion("สภาพแวดล้อมเบื้องหน้า: ไม่สามารถวิเคราะห์ได้")
                }
            }
        }
    }
    
    /// อ่านค่าความลึก (เมตร) จากพิกเซลของ Depth Map ณ พิกัดเป้าหมาย (ค่าปกติ 0.0 - 1.0)
    private func getDepthAtPoint(pixelBuffer: CVPixelBuffer, normalizedX: CGFloat, normalizedY: CGFloat) -> Float {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        
        let x = Int(normalizedX * CGFloat(width - 1))
        // Bounding box ของ Vision เริ่มจากมุมล่างซ้าย แต่ CVPixelBuffer/DepthMap เริ่มจากมุมบนซ้าย
        let y = Int((1.0 - normalizedY) * CGFloat(height - 1))
        
        guard x >= 0 && x < width && y >= 0 && y < height else { return 0.0 }
        
        let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer)
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
        let rowData = baseAddress?.advanced(by: y * bytesPerRow)
        let depthPointer = rowData?.assumingMemoryBound(to: Float32.self)
        
        let rawDepth = depthPointer?[x] ?? 0.0
        
        // กรองค่าที่เป็น NaN หรือค่าไม่สมเหตุสมผลออก
        return (rawDepth.isNaN || rawDepth.isInfinite) ? 0.0 : rawDepth
    }
}
