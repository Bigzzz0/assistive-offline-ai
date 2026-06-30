import Speech
import AVFoundation

/// คลาสจัดการการรับคำสั่งด้วยเสียงพูดภาษาไทยแบบออฟไลน์สำหรับ iOS
class SpeechInputManager: ObservableObject {
    static let shared = SpeechInputManager()
    
    private let speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "th-TH"))
    private var recognitionRequest: SFSpeechAudioBufferRecognitionRequest?
    private var recognitionTask: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()
    
    @Published var isRecording = false
    @Published var recognizedText = ""
    @Published var statusMessage = ""
    
    private func checkSpeechAuthorization(completion: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async {
                switch status {
                case .authorized:
                    completion(true)
                default:
                    completion(false)
                }
            }
        }
    }
    
    /// เริ่มต้นการฟังเสียงพูด และถอดความเป็นตัวหนังสือภาษาไทย
    func startListening(onTranscription: @escaping (String) -> Void, completion: @escaping (String?, Error?) -> Void) {
        guard !isRecording else { return }
        
        stopListening()
        
        checkSpeechAuthorization { [weak self] authorized in
            guard let self = self else { return }
            guard authorized else {
                completion(nil, NSError(domain: "SpeechInputManager", code: 1, userInfo: [NSLocalizedDescriptionKey: "ไม่ได้รับอนุญาตให้เข้าถึงการจำแนกคำพูด"]))
                return
            }
            
            do {
                try self.setupAndStartAudioEngine(onTranscription: onTranscription)
                completion(nil, nil)
            } catch {
                self.stopListening()
                completion(nil, error)
            }
        }
    }
    
    private func setupAndStartAudioEngine(onTranscription: @escaping (String) -> Void) throws {
        recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        guard let recognitionRequest = recognitionRequest else {
            throw NSError(domain: "SpeechInputManager", code: 2, userInfo: [NSLocalizedDescriptionKey: "ไม่สามารถเริ่มต้นระบบบันทึกเสียงได้"])
        }
        
        recognitionRequest.shouldReportPartialResults = true
        if #available(iOS 13.0, *) {
            recognitionRequest.requiresOnDeviceRecognition = false // ใช้ on-device หรือ server (สลับอัตโนมัติ)
        }
        
        let audioSession = AVAudioSession.sharedInstance()
        // กำหนดหมวดหมู่เป็น .playAndRecord เพื่อให้สามารถเล่นเสียง TTS กลับมาได้สะดวกหลังจากรับเสียงเสร็จ
        try audioSession.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker, .allowBluetooth])
        try audioSession.setActive(true, options: .notifyOthersOnDeactivation)
        
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            recognitionRequest.append(buffer)
        }
        
        audioEngine.prepare()
        try audioEngine.start()
        
        isRecording = true
        recognizedText = ""
        statusMessage = "กำลังฟังเสียงพูดของคุณ..."
        
        recognitionTask = speechRecognizer?.recognitionTask(with: recognitionRequest) { [weak self] result, error in
            guard let self = self else { return }
            
            if let result = result {
                let transcription = result.bestTranscription.formattedString
                DispatchQueue.main.async {
                    self.recognizedText = transcription
                    onTranscription(transcription)
                }
            }
            
            if error != nil || result?.isFinal == true {
                // บันทึกเสียงเสร็จหรือมี error ให้หยุดการบันทึก
                DispatchQueue.main.async {
                    self.stopListening()
                }
            }
        }
    }
    
    /// หยุดการบันทึกและจำแนกเสียงพูด
    func stopListening() {
        guard isRecording else { return }
        
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        recognitionRequest?.endAudio()
        recognitionTask?.cancel()
        
        recognitionRequest = nil
        recognitionTask = nil
        isRecording = false
        statusMessage = ""
        
        // คืนค่า Audio Session กลับเป็นค่าเริ่มต้น
        let audioSession = AVAudioSession.sharedInstance()
        try? audioSession.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
        try? audioSession.setActive(true)
    }
}
