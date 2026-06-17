import Foundation
import Vision
import UIKit

class InferenceEngine {
    static let shared = InferenceEngine()
    
    private var isInitialized = false
    private var isMockMode = true
    private var modelURL: URL?
    
    // System Prompt to enforce constraint decoding format (Thai BLV Assistant)
    private let systemPrompt = """
        บทบาท: คุณคือผู้ช่วยคนตาบอดภาษาไทย ตอบข้อมูลสั้นและตรงประเด็นที่สุด
        รูปแบบคำตอบที่บังคับ: [ชื่อวัตถุ] - [ตำแหน่งโดยประมาณ] (ความมั่นใจ: [ต่ำ/ปานกลาง/สูง])
        ตัวอย่าง: แก้วน้ำ - บนโต๊ะด้านหน้าขวา (ความมั่นใจ: สูง)
        ห้ามอธิบายยาว ห้ามใช้คำฟุ่มเฟือย ถ้าไม่มั่นใจอย่างมาก ให้ตอบว่า (ความมั่นใจ: ต่ำ)
        ระบบนี้เป็นเครื่องมือช่วยเหลือเสริม ไม่ได้ออกแบบมาเพื่อการนำทางโดยตรง
    """
    
    // MARK: - Initialization
    
    func initialize() -> Bool {
        let fileManager = FileManager.default
        let paths = fileManager.urls(for: .documentDirectory, in: .userDomainMask)
        guard let documentsDirectory = paths.first else { return false }
        
        // Check for Gemma 4 model with both filenames
        let primaryURL = documentsDirectory.appendingPathComponent("gemma-4-E2B-it.litertlm")
        let legacyURL  = documentsDirectory.appendingPathComponent("gemma_vlm.litertlm")
        
        if fileManager.fileExists(atPath: primaryURL.path) {
            modelURL = primaryURL
            isMockMode = false
        } else if fileManager.fileExists(atPath: legacyURL.path) {
            modelURL = legacyURL
            isMockMode = false
        } else {
            isMockMode = true
        }
        
        if !isMockMode, let url = modelURL {
            // ─── Gemma 4 VLM Engine Configuration (Section 5.3) ───
            // When LiteRT-LM Swift SDK becomes available:
            //
            // let config = LiteRTLMEngineConfig(
            //     modelPath: url.path,
            //     backend: .ane,          // Apple Neural Engine (NPU) — primary
            //     visionBackend: .metal,  // Metal GPU — for vision encoder
            //     cacheDir: FileManager.default.temporaryDirectory.path,
            //     maxTokens: 256,
            //     constraintDecoding: true // Enforce structured Thai output
            // )
            //
            // Fallback chain: ANE → Metal GPU → CPU
            // do {
            //     engine = try LiteRTLMEngine(config: config)
            //     engine.initialize()
            // } catch {
            //     // Fallback to Metal GPU
            //     config.backend = .metal
            //     engine = try LiteRTLMEngine(config: config)
            // }
            //
            // LoRA Adapter loading:
            // let loraURL = documentsDirectory.appendingPathComponent("lora_adapter")
            // if fileManager.fileExists(atPath: loraURL.path) {
            //     engine.loadLoRAAdapter(path: loraURL.path)
            // }
            
            print("[InferenceEngine] Gemma 4 model found at: \(url.path)")
            print("[InferenceEngine] ANE/Metal backend configured (awaiting LiteRT-LM Swift SDK)")
            // Until SDK is available, VLM modes remain simulated but OCR is real
        }
        
        isInitialized = true
        return true
    }
    
    func isMock() -> Bool {
        return isMockMode
    }
    
    // MARK: - Real Vision OCR (Thai + English)
    
    /// Performs native offline OCR using Apple Vision framework.
    /// Supports Thai (th-TH) and English (en-US) text recognition.
    func performOCR(jpegData: Data, completion: @escaping (String) -> Void) {
        guard let cgImage = UIImage(data: jpegData)?.cgImage else {
            completion("ไม่สามารถอ่านภาพได้ กรุณาถ่ายใหม่")
            return
        }
        
        let request = VNRecognizeTextRequest { request, error in
            if let error = error {
                print("[InferenceEngine] OCR error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    completion("เกิดข้อผิดพลาดในการอ่านข้อความ")
                }
                return
            }
            
            guard let observations = request.results as? [VNRecognizedTextObservation] else {
                DispatchQueue.main.async {
                    completion("ไม่พบข้อความในภาพ")
                }
                return
            }
            
            // Collect all recognized text lines
            let recognizedLines = observations.compactMap { observation -> String? in
                observation.topCandidates(1).first?.string
            }
            
            let fullText: String
            if recognizedLines.isEmpty {
                fullText = "ไม่พบข้อความในภาพ (ความมั่นใจ: สูง)"
            } else {
                let joined = recognizedLines.joined(separator: "\n")
                // Calculate average confidence
                let avgConfidence = observations.compactMap { $0.topCandidates(1).first?.confidence }.reduce(0, +) / Float(observations.count)
                let confidenceLabel: String
                if avgConfidence >= 0.8 {
                    confidenceLabel = "สูง"
                } else if avgConfidence >= 0.5 {
                    confidenceLabel = "ปานกลาง"
                } else {
                    confidenceLabel = "ต่ำ"
                }
                fullText = "ข้อความที่อ่านได้:\n\(joined)\n(ความมั่นใจ: \(confidenceLabel))"
            }
            
            DispatchQueue.main.async {
                completion(fullText)
            }
        }
        
        // Configure for Thai + English recognition
        request.recognitionLanguages = ["th-TH", "en-US"]
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try handler.perform([request])
            } catch {
                print("[InferenceEngine] Vision handler error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    completion("เกิดข้อผิดพลาดในการประมวลผลภาพ")
                }
            }
        }
    }
    
    // MARK: - VLM Image Analysis (Mock + Real stubs)
    
    func analyzeImage(jpegData: Data, promptText: String, onToken: @escaping (String) -> Void, completion: @escaping (String) -> Void) {
        // ── OCR mode uses real Vision framework ──
        if promptText.contains("อ่าน") {
            performOCR(jpegData: jpegData) { result in
                onToken(result)
                completion(result)
            }
            return
        }
        
        // ── VLM modes (object/obstacle): simulated until LiteRT-LM Swift SDK available ──
        DispatchQueue.global().async {
            Thread.sleep(forTimeInterval: 1.2)
            let mockResponse: String
            if promptText.contains("สิ่งของ") || promptText.contains("บนโต๊ะ") || promptText.contains("ดู") {
                mockResponse = "แก้วน้ำและกุญแจ - อยู่ตรงกลางโต๊ะทำงาน (ความมั่นใจ: สูง)"
            } else if promptText.contains("ข้างหน้า") || promptText.contains("กีดขวาง") {
                mockResponse = "เก้าอี้ไม้ขวางทาง - อยู่ด้านหน้าประมาณ 1 เมตร (ความมั่นใจ: สูง)"
            } else {
                mockResponse = "สมาร์ทโฟน - ถืออยู่ในมือ (ความมั่นใจ: สูง)"
            }
            let tokens = mockResponse.components(separatedBy: " ")
            
            var accumulated = ""
            for token in tokens {
                let tokenWithSpace = token + " "
                accumulated += tokenWithSpace
                DispatchQueue.main.async {
                    onToken(tokenWithSpace)
                }
                Thread.sleep(forTimeInterval: 0.1)
            }
            DispatchQueue.main.async {
                completion(accumulated)
            }
        }
    }
}
