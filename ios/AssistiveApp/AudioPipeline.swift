import AVFoundation

class AudioPipeline: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = AudioPipeline()
    
    private let speechSynthesizer = AVSpeechSynthesizer()
    private var audioEngine = AVAudioEngine()
    private var onSpeechDone: (() -> Void)?
    
    override init() {
        super.init()
        speechSynthesizer.delegate = self
    }
    
    func startListening(onCommandDetected: @escaping (String) -> Void) {
        let inputNode = audioEngine.inputNode
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        
        inputNode.installTap(onBus: 0, bufferSize: 1024, format: recordingFormat) { buffer, _ in
            // Parse audio data for ASR. In production, we pass these raw PCM samples to Sherpa-ONNX.
            // For CPU/memory safety and mock testing, we can simulate ASR keywords detection.
        }
        
        audioEngine.prepare()
        try? audioEngine.start()
    }
    
    func stopListening() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
    }
    
    func speak(_ text: String, completion: @escaping () -> Void = {}) {
        stopSpeaking()
        self.onSpeechDone = completion
        
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "th-TH")
        utterance.rate = 0.5
        
        speechSynthesizer.speak(utterance)
    }
    
    func stopSpeaking() {
        if speechSynthesizer.isSpeaking {
            speechSynthesizer.stopSpeaking(at: .immediate)
        }
    }
    
    // AVSpeechSynthesizerDelegate methods
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        onSpeechDone?()
        onSpeechDone = nil
    }
    
    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        onSpeechDone?()
        onSpeechDone = nil
    }
}
