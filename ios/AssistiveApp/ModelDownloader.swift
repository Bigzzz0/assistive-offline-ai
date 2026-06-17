import Foundation

/// ไฟล์โมเดลที่ต้องดาวน์โหลด
enum IOSModelFile: String, CaseIterable {
    case vlm = "gemma-4-E2B-it.litertlm"
    
    var displayName: String {
        switch self {
        case .vlm: return "Gemma 4 E2B VLM (~1.5 GB)"
        }
    }
    
    var url: String {
        switch self {
        case .vlm:
            return "https://huggingface.co/litert-community/gemma-4-E2B-it/resolve/main/gemma-4-E2B-it.litertlm"
        }
    }
    
    var destinationURL: URL {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        return docs.appendingPathComponent(rawValue)
    }
    
    var isDownloaded: Bool {
        FileManager.default.fileExists(atPath: destinationURL.path)
    }
    
    /// ขนาดไฟล์ที่ดาวน์โหลดแล้ว (bytes)
    var downloadedSize: Int64 {
        guard let attrs = try? FileManager.default.attributesOfItem(atPath: destinationURL.path),
              let size = attrs[.size] as? Int64 else { return 0 }
        return size
    }
}

/// ดาวน์โหลดโมเดล AI สำหรับ iOS พร้อม progress tracking และ resume support
class IOSModelDownloader: NSObject, ObservableObject, URLSessionDownloadDelegate {
    static let shared = IOSModelDownloader()
    
    @Published var isDownloading = false
    @Published var progress: Float = 0.0
    @Published var statusMessage: String = ""
    @Published var downloadedBytes: Int64 = 0
    @Published var totalBytes: Int64 = 0
    @Published var error: String? = nil
    @Published var isComplete = false
    
    private var downloadTask: URLSessionDownloadTask?
    private var currentFile: IOSModelFile?
    private var session: URLSession?
    private var resumeData: Data?
    
    override init() {
        super.init()
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 60
        config.timeoutIntervalForResource = 3600 // 1 hour max for large model
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
    }
    
    func startDownload(_ file: IOSModelFile) {
        guard !isDownloading else { return }
        
        // ถ้ามีไฟล์แล้ว ไม่ต้องโหลดซ้ำ
        if file.isDownloaded {
            statusMessage = "โมเดลพร้อมใช้งานแล้ว ✅"
            isComplete = true
            return
        }
        
        currentFile = file
        isDownloading = true
        progress = 0.0
        error = nil
        isComplete = false
        statusMessage = "กำลังเชื่อมต่อ HuggingFace..."
        
        guard let url = URL(string: file.url) else {
            error = "URL ไม่ถูกต้อง"
            isDownloading = false
            return
        }
        
        var request = URLRequest(url: url)
        request.setValue("AssistiveOfflineAI-iOS/1.0", forHTTPHeaderField: "User-Agent")
        
        // Resume support: ถ้ามี resumeData ใช้ต่อ
        if let resumeData = resumeData {
            downloadTask = session?.downloadTask(withResumeData: resumeData)
        } else {
            downloadTask = session?.downloadTask(with: request)
        }
        
        downloadTask?.resume()
    }
    
    func cancelDownload() {
        downloadTask?.cancel(byProducingResumeData: { [weak self] data in
            self?.resumeData = data
        })
        isDownloading = false
        statusMessage = "ยกเลิกการดาวน์โหลด — สามารถดาวน์โหลดต่อได้"
    }
    
    func deleteModel(_ file: IOSModelFile) {
        try? FileManager.default.removeItem(at: file.destinationURL)
        isComplete = false
        statusMessage = "ลบโมเดลเรียบร้อย"
    }
    
    // MARK: - URLSessionDownloadDelegate
    
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let file = currentFile else { return }
        
        do {
            // ลบไฟล์เก่าถ้ามี
            if FileManager.default.fileExists(atPath: file.destinationURL.path) {
                try FileManager.default.removeItem(at: file.destinationURL)
            }
            // ย้ายไฟล์จาก temp → Documents
            try FileManager.default.moveItem(at: location, to: file.destinationURL)
            
            isDownloading = false
            isComplete = true
            progress = 1.0
            statusMessage = "ดาวน์โหลดเสร็จสิ้น ✅ — กรุณารีสตาร์ทแอปเพื่อใช้งานโมเดล"
            resumeData = nil
            
            print("[ModelDownloader] Model saved to: \(file.destinationURL.path)")
        } catch {
            self.error = "ไม่สามารถบันทึกไฟล์: \(error.localizedDescription)"
            isDownloading = false
        }
    }
    
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        downloadedBytes = totalBytesWritten
        totalBytes = totalBytesExpectedToWrite
        
        if totalBytesExpectedToWrite > 0 {
            progress = Float(totalBytesWritten) / Float(totalBytesExpectedToWrite)
        }
        
        let downloadedMB = Float(totalBytesWritten) / 1_048_576.0
        let totalMB = Float(totalBytesExpectedToWrite) / 1_048_576.0
        statusMessage = String(format: "ดาวน์โหลด %.0f / %.0f MB (%.0f%%)", downloadedMB, totalMB, progress * 100)
    }
    
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error = error {
            let nsError = error as NSError
            // Cancelled by user — resume data saved
            if nsError.code == NSURLErrorCancelled {
                return
            }
            self.error = "ดาวน์โหลดล้มเหลว: \(error.localizedDescription)"
            isDownloading = false
            statusMessage = "เกิดข้อผิดพลาด — แตะเพื่อลองใหม่"
        }
    }
}
