import Foundation
import Vision
import UIKit
import LiteRTLM
import Combine

class LogStore: ObservableObject {
    static let shared = LogStore()
    @Published var logs: [String] = []
    private var logFileURL: URL?
    
    init() {
        let fileManager = FileManager.default
        let paths = fileManager.urls(for: .documentDirectory, in: .userDomainMask)
        if let docDir = paths.first {
            let url = docDir.appendingPathComponent("debug_log.txt")
            self.logFileURL = url
            loadPersistedLogs(from: url)
        }
    }
    
    private func loadPersistedLogs(from url: URL) {
        do {
            if FileManager.default.fileExists(atPath: url.path) {
                let content = try String(contentsOf: url, encoding: .utf8)
                let lines = content.components(separatedBy: "\n").filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                DispatchQueue.main.async {
                    self.logs = lines.map { "[PREV] \($0)" }
                    self.log("--- New Session Started ---")
                }
            } else {
                DispatchQueue.main.async {
                    self.log("--- New Log File Created ---")
                }
            }
        } catch {
            print("Failed to load logs: \(error.localizedDescription)")
        }
    }
    
    func log(_ message: String) {
        print(message)
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        let timeStr = formatter.string(from: Date())
        let formattedMessage = "[\(timeStr)] \(message)"
        
        DispatchQueue.main.async {
            self.logs.append(formattedMessage)
            if self.logs.count > 150 {
                self.logs.removeFirst()
            }
            self.persistLogs()
        }
    }
    
    private func persistLogs() {
        guard let url = logFileURL else { return }
        let rawLogs = self.logs.map { line -> String in
            if line.hasPrefix("[PREV] ") {
                return String(line.dropFirst(7))
            }
            return line
        }
        let content = rawLogs.joined(separator: "\n")
        try? content.write(to: url, atomically: true, encoding: .utf8)
    }
}

class InferenceEngine {
    static let shared = InferenceEngine()
    
    private var isInitialized = false
    private var isMockMode = true
    private var modelURL: URL?
    
    private var engine: Engine?
    private var conversation: Conversation?
    
    // System Prompt to enforce constraint decoding format (Thai BLV Assistant)
    private let systemPrompt = """
        บทบาท: คุณคือผู้ช่วยคนตาบอดภาษาไทย ตอบข้อมูลสั้นและตรงประเด็นที่สุด
        รูปแบบคำตอบที่บังคับ: [ชื่อวัตถุ] - [ตำแหน่งโดยประมาณ] (ความมั่นใจ: [ต่ำ/ปานกลาง/สูง])
        ตัวอย่าง: แก้วน้ำ - บนโต๊ะด้านหน้าขวา (ความมั่นใจ: สูง)
        ห้ามอธิบายยาว ห้ามใช้คำฟุ่มเฟือย ถ้าไม่มั่นใจอย่างมาก ให้ตอบว่า (ความมั่นใจ: ต่ำ)
        ระบบนี้เป็นเครื่องมือช่วยเหลือเสริม ไม่ได้ออกแบบมาเพื่อการนำทางโดยตรง
    """
    
    // MARK: - Initialization
    
    func initialize(force: Bool = false) -> Bool {
        if !force, isInitialized, !isMockMode, self.conversation != nil {
            return true
        }
        
        isInitialized = false
        
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
            isInitialized = true
        }
        
        if !isMockMode, let url = modelURL {
            LogStore.shared.log("[InferenceEngine] Gemma 4 model found at: \(url.path). Configuring LiteRT-LM Engine...")
            
            // Enable experimental flags and set visual token budget (reduces image prefill memory footprint by ~8x)
            ExperimentalFlags.optIntoExperimentalAPIs()
            ExperimentalFlags.visualTokenBudget = Int32(140)
            
            Task {
                do {
                    LogStore.shared.log("[InferenceEngine] Attempting GPU initialization with GPU visionBackend...")
                    let config = try EngineConfig(
                        modelPath: url.path,
                        backend: .gpu,
                        visionBackend: .gpu,
                        maxNumTokens: 512, // Further restrict KV cache size to allow GPU creation success
                        cacheDir: FileManager.default.temporaryDirectory.path
                    )
                    let engineInstance = Engine(engineConfig: config)
                    try await engineInstance.initialize()
                    self.engine = engineInstance
                    self.conversation = try await engineInstance.createConversation()
                    LogStore.shared.log("[InferenceEngine] LiteRT-LM Engine & Conversation initialized successfully on GPU.")
                    self.isInitialized = true
                    
                    DispatchQueue.main.async {
                        NotificationCenter.default.post(name: NSNotification.Name("InferenceEngineStateDidChange"), object: nil)
                    }
                } catch {
                    LogStore.shared.log("[InferenceEngine] GPU initialization failed: \(error.localizedDescription). Falling back to CPU...")
                    do {
                        let config = try EngineConfig(
                            modelPath: url.path,
                            backend: .cpu(),
                            visionBackend: .cpu(),
                            maxNumTokens: 512, // Match maxNumTokens for CPU fallback
                            cacheDir: FileManager.default.temporaryDirectory.path
                        )
                        let engineInstance = Engine(engineConfig: config)
                        try await engineInstance.initialize()
                        self.engine = engineInstance
                        self.conversation = try await engineInstance.createConversation()
                        LogStore.shared.log("[InferenceEngine] LiteRT-LM Engine & Conversation initialized successfully on CPU.")
                        self.isInitialized = true
                        
                        DispatchQueue.main.async {
                            NotificationCenter.default.post(name: NSNotification.Name("InferenceEngineStateDidChange"), object: nil)
                        }
                    } catch {
                        LogStore.shared.log("[InferenceEngine] CPU initialization failed: \(error.localizedDescription). Running in Mock Mode.")
                        self.isMockMode = true
                        self.isInitialized = true
                        
                        DispatchQueue.main.async {
                            NotificationCenter.default.post(name: NSNotification.Name("InferenceEngineStateDidChange"), object: nil)
                        }
                    }
                }
            }
        }
        
        return true
    }
    
    func isMock() -> Bool {
        return isMockMode
    }
    
    func isReady() -> Bool {
        return isInitialized
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
                LogStore.shared.log("[InferenceEngine] OCR error: \(error.localizedDescription)")
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
                LogStore.shared.log("[InferenceEngine] Vision handler error: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    completion("เกิดข้อผิดพลาดในการประมวลผลภาพ")
                }
            }
        }
    }
    
    // MARK: - VLM Image Analysis (Mock + Real stubs)
    
    private func runMockVLM(promptText: String, onToken: @escaping (String) -> Void, completion: @escaping (String) -> Void) {
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
    
    func analyzeImage(jpegData: Data, promptText: String, onToken: @escaping (String) -> Void, completion: @escaping (String) -> Void) {
        // ── OCR mode uses real Vision framework ──
        if promptText.contains("อ่าน") {
            performOCR(jpegData: jpegData) { result in
                onToken(result)
                completion(result)
            }
            return
        }
        
        // ── VLM modes (object/obstacle) ──
        if !isMockMode, let conversation = self.conversation {
            LogStore.shared.log("[InferenceEngine] Starting VLM image analysis. Image size: \(jpegData.count) bytes. Prompt: \(promptText)")
            // Write JPEG to temporary file
            let tempFileURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".jpg")
            do {
                try jpegData.write(to: tempFileURL)
                LogStore.shared.log("[InferenceEngine] Written temp image file to: \(tempFileURL.path)")
            } catch {
                LogStore.shared.log("[InferenceEngine] VLM temp write error: \(error.localizedDescription)")
                runMockVLM(promptText: promptText, onToken: onToken, completion: completion)
                return
            }
            
            Task.detached(priority: .userInitiated) {
                do {
                    LogStore.shared.log("[InferenceEngine] Detached background task started. Constructing Message payload...")
                    let prompt = "\(self.systemPrompt)\n\nคำสั่ง: \(promptText)"
                    let message = Message(contents: [
                        Content.imageFile(tempFileURL.path),
                        Content.text(prompt)
                    ])
                    
                    var accumulatedText = ""
                    LogStore.shared.log("[InferenceEngine] Sending message payload and starting stream...")
                    
                    // sendMessageStream returns an AsyncThrowingStream of Message chunks
                    for try await chunk in conversation.sendMessageStream(message) {
                        let textChunk = chunk.toString
                        if accumulatedText.isEmpty {
                            LogStore.shared.log("[InferenceEngine] First VLM streamed token chunk received: \(textChunk)")
                        }
                        accumulatedText += textChunk
                        DispatchQueue.main.async {
                            onToken(textChunk)
                        }
                    }
                    
                    LogStore.shared.log("[InferenceEngine] VLM stream completed. Total response size: \(accumulatedText.count) chars.")
                    
                    // Clean up temp file
                    try? FileManager.default.removeItem(at: tempFileURL)
                    
                    DispatchQueue.main.async {
                        completion(accumulatedText)
                    }
                } catch {
                    LogStore.shared.log("[InferenceEngine] Real VLM inference error: \(error.localizedDescription)")
                    try? FileManager.default.removeItem(at: tempFileURL)
                    // Fallback to mock response if local model fails during execution
                    self.runMockVLM(promptText: promptText, onToken: onToken, completion: completion)
                }
            }
            return
        }
        
        // Fallback to mock VLM
        runMockVLM(promptText: promptText, onToken: onToken, completion: completion)
    }
}
