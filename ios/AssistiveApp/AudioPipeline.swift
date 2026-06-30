import AVFoundation
import UIKit
import simd

class AudioPipeline: NSObject, AVSpeechSynthesizerDelegate {
    static let shared = AudioPipeline()
    
    private let speechSynthesizer = AVSpeechSynthesizer()
    private var audioEngine = AVAudioEngine()
    private var environmentNode = AVAudioEnvironmentNode()
    private var onSpeechDone: (() -> Void)?
    
    private(set) var speechRate: Float = {
        let saved = UserDefaults.standard.float(forKey: "TTS_SpeechRate")
        return saved > 0.0 ? saved : 0.5
    }()
    
    // Tone Oscillator properties
    private var toneSourceNode: AVAudioSourceNode?
    private var isPlayingTone = false
    private var currentFrequency: Float = 440.0
    private var toneVolume: Float = 0.0
    private var targetVolume: Float = 0.0
    private var pulseTimer: Timer?
    
    private(set) var isBeepAlertMuted: Bool = {
        return UserDefaults.standard.bool(forKey: "isBeepAlertMuted")
    }()
    
    func setBeepAlertMuted(_ muted: Bool) {
        self.isBeepAlertMuted = muted
        UserDefaults.standard.set(muted, forKey: "isBeepAlertMuted")
        if muted {
            stopTone()
        }
    }
    
    override init() {
        super.init()
        speechSynthesizer.delegate = self
        setupAudioSession()
        setupEnvironmentNode()
    }
    
    private func setupAudioSession() {
        let audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession.setCategory(.playAndRecord, mode: .default, options: [.mixWithOthers, .allowBluetooth, .defaultToSpeaker])
            if #available(iOS 13.0, *) {
                try audioSession.setAllowHapticsAndSystemSoundsDuringRecording(true)
            }
            try audioSession.setActive(true)
        } catch {
            print("Failed to set up AVAudioSession: \(error.localizedDescription)")
        }
    }
    
    private func setupEnvironmentNode() {
        audioEngine.attach(environmentNode)
        let outputFormat = audioEngine.outputNode.outputFormat(forBus: 0)
        audioEngine.connect(environmentNode, to: audioEngine.mainMixerNode, format: outputFormat)
    }
    
    func startTone() {
        guard !isPlayingTone else { return }
        isPlayingTone = true
        
        let sampleRate = audioEngine.outputNode.outputFormat(forBus: 0).sampleRate
        var phase: Float = 0.0
        
        let sourceNode = AVAudioSourceNode { [weak self] (_, _, frameCount, audioBufferList) -> OSStatus in
            guard let self = self else { return noErr }
            let ablPointer = UnsafeMutableAudioBufferListPointer(audioBufferList)
            for buffer in ablPointer {
                let buf: UnsafeMutableBufferPointer<Float> = UnsafeMutableBufferPointer(buffer)
                for frame in 0..<Int(frameCount) {
                    let sampleVal = sin(phase) * self.toneVolume
                    buf[frame] = sampleVal
                    
                    phase += 2.0 * Float.pi * self.currentFrequency / Float(sampleRate)
                    if phase >= 2.0 * Float.pi {
                        phase -= 2.0 * Float.pi
                    }
                }
            }
            return noErr
        }
        
        self.toneSourceNode = sourceNode
        audioEngine.attach(sourceNode)
        
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        audioEngine.connect(sourceNode, to: environmentNode, format: format)
        sourceNode.renderingAlgorithm = .HRTFHQ
        sourceNode.position = AVAudio3DPoint(x: 0.0, y: 0.0, z: -1.0) // Default straight ahead (using Float literals)
        
        if !audioEngine.isRunning {
            try? audioEngine.start()
        }
    }
    
    func stopTone() {
        pulseTimer?.invalidate()
        pulseTimer = nil
        
        guard isPlayingTone, let sourceNode = toneSourceNode else { return }
        isPlayingTone = false
        audioEngine.disconnectNodeInput(environmentNode)
        audioEngine.detach(sourceNode)
        toneSourceNode = nil
    }
    
    func setToneFrequency(_ freq: Float, volume: Float) {
        self.currentFrequency = freq
        self.targetVolume = volume
        if freq > 0 && !isPlayingTone {
            startTone()
        }
    }
    
    func updateDistanceAlert(distance: Float, position: simd_float3? = nil) {
        let freq: Float
        let interval: Double
        let vol: Float
        let hapticLevel: Int
        
        if distance < 1.5 {
            freq = 800.0
            interval = 0.15
            vol = 0.6
            hapticLevel = 3
        } else if distance < 3.0 {
            freq = 400.0
            interval = 0.50
            vol = 0.4
            hapticLevel = 2
        } else {
            freq = 0.0
            interval = 0.0
            vol = 0.0
            hapticLevel = 0
        }
        
        DispatchQueue.main.async {
            if hapticLevel == 3 {
                HapticManager.shared.vibrateDanger()
            } else if hapticLevel == 2 {
                HapticManager.shared.vibrateWarning()
            }
            
            if let pos = position, let node = self.toneSourceNode {
                node.position = AVAudio3DPoint(x: pos.x, y: pos.y, z: pos.z)
            }
            
            if self.isBeepAlertMuted {
                self.stopTone()
                return
            }
            
            self.setToneFrequency(freq, volume: vol)
            self.setupBeepPulsing(interval: interval)
        }
    }

    
    private func setupBeepPulsing(interval: Double) {
        pulseTimer?.invalidate()
        guard interval > 0 else {
            self.toneVolume = 0.0
            return
        }
        
        pulseTimer = Timer.scheduledTimer(withTimeInterval: interval, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            self.toneVolume = self.targetVolume
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                self.toneVolume = 0.0
            }
        }
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
        
        // Strip out technical confidence labels to keep speech clean
        let cleanText = text
            .replacingOccurrences(of: "\\(ความมั่นใจ:\\s*[^\\)]+\\)", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        
        if UIAccessibility.isVoiceOverRunning {
            self.onSpeechDone = nil
            UIAccessibility.post(notification: .announcement, argument: cleanText)
            DispatchQueue.main.async {
                completion()
            }
        } else {
            self.onSpeechDone = completion
            
            let utterance = AVSpeechUtterance(string: cleanText)
            utterance.voice = AVSpeechSynthesisVoice(language: "th-TH")
            utterance.rate = speechRate
            
            speechSynthesizer.speak(utterance)
        }
    }
    
    func increaseSpeechRate() -> Float {
        var rate = speechRate + 0.05
        if rate > 0.85 { rate = 0.85 }
        speechRate = rate
        UserDefaults.standard.set(rate, forKey: "TTS_SpeechRate")
        return rate
    }
    
    func decreaseSpeechRate() -> Float {
        var rate = speechRate - 0.05
        if rate < 0.25 { rate = 0.25 }
        speechRate = rate
        UserDefaults.standard.set(rate, forKey: "TTS_SpeechRate")
        return rate
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
