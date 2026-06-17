import SwiftUI
import AVFoundation

enum ActiveMode: String, CaseIterable {
    case read = "📖 อ่านหนังสือ (OCR)"
    case object = "👁️ หาวัตถุบนโต๊ะ"
    case obstacle = "🚧 ตรวจสิ่งกีดขวาง"
    
    var command: String {
        switch self {
        case .read: return "อ่าน"
        case .object: return "ดู"
        case .obstacle: return "ข้างหน้า"
        }
    }
    
    var speech: String {
        switch self {
        case .read: return "โหมดอ่านหนังสือ"
        case .object: return "โหมดหาวัตถุ"
        case .obstacle: return "โหมดตรวจจับสิ่งกีดขวาง"
        }
    }
    
    func next() -> ActiveMode {
        let all = ActiveMode.allCases
        guard let idx = all.firstIndex(of: self) else { return .object }
        return all[(idx + 1) % all.count]
    }
    
    func previous() -> ActiveMode {
        let all = ActiveMode.allCases
        guard let idx = all.firstIndex(of: self) else { return .object }
        return all[(idx - 1 + all.count) % all.count]
    }
}

struct ContentView: View {
    @State private var currentMode: ActiveMode = .object
    @State private var statusText: String = "กำลังเริ่มต้นระบบ..."
    @State private var aiResultText: String = ""
    @State private var showDevPanel: Bool = false
    @State private var lastLatencyMs: Int = 0
    @State private var isProcessing: Bool = false
    
    // Camera permission state
    @State private var cameraAuthorized: Bool = false
    @State private var cameraSessionStarted: Bool = false
    
    // Performance Mock Metrics for UI presentation
    @State private var memoryUsageMB: Float = 0
    @State private var cpuTempCelsius: Float = 0
    @State private var batteryDrain: Float = 0
    @State private var totalInferences: Int = 0
    @State private var averageLatencyMs: Int = 0
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // ---- Header ----
                HStack {
                    Text("ASSISTIVE OFFLINE AI")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    Text(InferenceEngine.shared.isMock() ? "⚠️ Mock" : "✅ Real Mode")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(InferenceEngine.shared.isMock() ? Color.gray : Color.green)
                        .cornerRadius(16)
                }
                .padding(.top, 10)
                
                // ---- Active Mode Banner ----
                VStack(spacing: 4) {
                    Text("โหมดใช้งานสัมผัสปัจจุบัน")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.gray)
                    Text(currentMode.rawValue)
                        .font(.system(size: 22, weight: .bold))
                        .foregroundColor(.white)
                    Text("ปัดซ้าย/ขวาเพื่อเปลี่ยน | แตะสองครั้งที่กล้องเพื่อทำงาน")
                        .font(.system(size: 11))
                        .foregroundColor(Color.blue)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(red: 0.12, green: 0.16, blue: 0.23))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.blue, lineWidth: 2)
                )
                .accessibilityElement(children: .combine)
                .accessibilityLabel("โหมดใช้งานสัมผัสปัจจุบันคือ \(currentMode.speech)")
                
                // ---- Live Camera Viewport ----
                ZStack {
                    if cameraAuthorized, let session = VisionPipeline.shared.session {
                        CameraPreviewView(session: session)
                    } else {
                        Color.black
                        VStack {
                            Image(systemName: "camera.fill")
                                .font(.system(size: 40))
                                .foregroundColor(.gray)
                            Text(cameraAuthorized ? "กำลังเตรียมกล้อง..." : "กรุณาอนุญาตการใช้กล้อง")
                                .font(.system(size: 12))
                                .foregroundColor(.gray)
                                .padding(.top, 4)
                        }
                    }
                    
                    VStack {
                        Spacer()
                        Text(isProcessing ? "กำลังประมวลผล..." : "แตะสองครั้งเพื่อวิเคราะห์ภาพ")
                            .font(.system(size: 12))
                            .foregroundColor(.white.opacity(0.7))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.black.opacity(0.5))
                            .cornerRadius(8)
                            .padding(.bottom, 8)
                    }
                }
                .frame(height: 250)
                .cornerRadius(16)
                .gesture(
                    TapGesture(count: 2).onEnded {
                        triggerCommand(currentMode.command)
                    }
                    .simultaneously(with: TapGesture(count: 1).onEnded {
                        announceMode()
                    })
                )
                .accessibilityElement(children: .combine)
                .accessibilityLabel("หน้าต่างกล้อง โหมดปัจจุบันคือ \(currentMode.speech). แตะสองครั้งเพื่อเริ่มทำงาน หรือปัดซ้ายขวาเพื่อสลับโหมด")
                
                // ---- Status Card ----
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("สถานะระบบ")
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                        Text(statusText)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.green)
                    }
                    Spacer()
                    if lastLatencyMs > 0 {
                        Text("\(lastLatencyMs)ms")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(.green)
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(red: 0.07, green: 0.07, blue: 0.07))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(Color.green, lineWidth: 2)
                )
                .accessibilityElement(children: .combine)
                .accessibilityLabel("สถานะระบบ \(statusText) การวิเคราะห์ภาพล่าสุดใช้เวลา \(lastLatencyMs) มิลลิวินาที")
                
                // ---- AI Output Box ----
                VStack {
                    Text(aiResultText.isEmpty ? "ระบบจะอธิบายภาพผ่านเสียงพูดและการสั่น\nปัดซ้ายขวาเพื่อปรับโหมด หรือแตะกล้องเพื่อเริ่ม" : aiResultText)
                        .font(.system(size: aiResultText.isEmpty ? 16 : 20, weight: .medium))
                        .foregroundColor(aiResultText.isEmpty ? .gray : .white)
                        .multilineTextAlignment(.center)
                        .padding()
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: 120)
                .background(Color.black)
                .cornerRadius(16)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(Color.white, lineWidth: 2)
                )
                .onTapGesture {
                    speakText(aiResultText.isEmpty ? "ระบบจะอธิบายภาพผ่านเสียงพูดและการสั่น" : aiResultText)
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel("คำอธิบายภาพจากเอไอ: \(aiResultText.isEmpty ? "ยังไม่มีคำอธิบาย แตะที่หน้าจอเพื่อวิเคราะห์" : aiResultText)")
                
                // ---- Stacked Action Buttons ----
                VStack(spacing: 12) {
                    Button(action: { triggerCommand("อ่าน") }) {
                        Text("📖 อ่านข้อความ (OCR)")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.blue)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มอ่านข้อความบนหนังสือ")
                    
                    Button(action: { triggerCommand("ดู") }) {
                        Text("👁️ ดูสิ่งของบนโต๊ะ")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.green)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มตรวจหาสิ่งของรอบตัว")
                    
                    Button(action: { triggerCommand("ข้างหน้า") }) {
                        Text("🚧 ตรวจสิ่งกีดขวางข้างหน้า")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.orange)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มตรวจสอบสิ่งกีดขวางทางเดินด้านหน้า")
                }
                
                // ---- Dev Panel Button ----
                Button(action: {
                    showDevPanel.toggle()
                    vibrateHaptic(level: 1)
                }) {
                    Text(showDevPanel ? "ซ่อนแผงผู้พัฒนา" : "📊 Dev Panel")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(.blue)
                        .padding()
                        .frame(maxWidth: .infinity)
                        .background(RoundedRectangle(cornerRadius: 8).stroke(Color.blue, lineWidth: 1))
                }
                
                // ---- Performance Panel ----
                if showDevPanel {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("📈 Performance Metrics")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundColor(Color.cyan)
                        
                        // Latency
                        iOSMetricRow(
                            label: "Inference Latency",
                            valueText: "\(lastLatencyMs) ms",
                            progress: Float(lastLatencyMs) / 5000.0,
                            color: lastLatencyMs < 3000 ? .green : .red,
                            accessibilityText: "เวลาประมวลผลเอไอภาพล่าสุด \(lastLatencyMs) มิลลิวินาที สถานะปกติ"
                        )
                        
                        // Memory
                        iOSMetricRow(
                            label: "Memory Usage",
                            valueText: String(format: "%.0f MB", memoryUsageMB),
                            progress: memoryUsageMB / 6000.0,
                            color: memoryUsageMB < 4000 ? .green : .red,
                            accessibilityText: "การใช้งานหน่วยความจำของแอป \(Int(memoryUsageMB)) เมกะไบต์"
                        )
                        
                        // Temperature
                        iOSMetricRow(
                            label: "Temperature",
                            valueText: String(format: "%.0f °C", cpuTempCelsius),
                            progress: cpuTempCelsius / 60.0,
                            color: cpuTempCelsius < 45 ? .green : (cpuTempCelsius < 55 ? .orange : .red),
                            accessibilityText: "อุณหภูมิหน่วยประมวลผล \(Int(cpuTempCelsius)) องศาเซลเซียส สถานะปกติ"
                        )
                        
                        // Battery
                        iOSMetricRow(
                            label: "Battery Drain",
                            valueText: String(format: "%.1f mAh/min", batteryDrain),
                            progress: batteryDrain / 20.0,
                            color: batteryDrain < 15 ? .green : .red,
                            accessibilityText: "อัตราสิ้นเปลืองพลังงานแบตเตอรี่ \(batteryDrain) มิลลิแอมป์ต่อนาที"
                        )
                    }
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(Color(red: 0.05, green: 0.08, blue: 0.15))
                    .cornerRadius(12)
                }
            }
            .padding()
        }
        .background(Color.black.edgesIgnoringSafeArea(.all))
        .gesture(
            DragGesture().onEnded { value in
                if value.translation.width > 100 { // Swipe Right -> previous
                    currentMode = currentMode.previous()
                    announceMode()
                } else if value.translation.width < -100 { // Swipe Left -> next
                    currentMode = currentMode.next()
                    announceMode()
                }
            }
        )
        .onAppear {
            requestCameraPermission()
            _ = InferenceEngine.shared.initialize()
            statusText = "ระบบพร้อมทำงาน"
        }
    }
    
    // MARK: - Camera Permission
    
    private func requestCameraPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            cameraAuthorized = true
            startCameraIfNeeded()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { granted in
                DispatchQueue.main.async {
                    cameraAuthorized = granted
                    if granted {
                        startCameraIfNeeded()
                    }
                }
            }
        default:
            cameraAuthorized = false
        }
    }
    
    private func startCameraIfNeeded() {
        guard !cameraSessionStarted else { return }
        cameraSessionStarted = true
        VisionPipeline.shared.startSession()
    }
    
    // MARK: - Actions
    
    private func triggerCommand(_ command: String) {
        guard !isProcessing else { return }
        isProcessing = true
        vibrateHaptic(level: 1)
        speakText("กำลังทำคำสั่ง \(command)")
        statusText = "กำลังวิเคราะห์ภาพ..."
        
        let startTime = Date()
        
        // Capture current frame from the live camera
        VisionPipeline.shared.captureCurrentFrame { jpegData in
            guard let jpegData = jpegData else {
                // No camera data — use mock
                self.statusText = "ไม่สามารถจับภาพจากกล้องได้"
                self.aiResultText = "กรุณาอนุญาตการใช้กล้องและลองอีกครั้ง"
                self.speakText(self.aiResultText)
                self.isProcessing = false
                return
            }
            
            // Route to InferenceEngine (real OCR for read mode, simulated for others)
            InferenceEngine.shared.analyzeImage(jpegData: jpegData, promptText: command, onToken: { _ in }) { result in
                let elapsed = Int(Date().timeIntervalSince(startTime) * 1000)
                self.lastLatencyMs = elapsed
                self.totalInferences += 1
                self.aiResultText = result
                self.statusText = "ระบบพร้อมทำงาน (\(elapsed)ms)"
                self.speakText(result)
                self.vibrateHaptic(level: 2)
                self.isProcessing = false
            }
        }
    }
    
    private func announceMode() {
        vibrateHaptic(level: 1)
        speakText(currentMode.speech)
    }
    
    private func speakText(_ text: String) {
        AudioPipeline.shared.speak(text)
    }
    
    private func vibrateHaptic(level: Int) {
        if level == 3 {
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.error)
        } else if level == 2 {
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.warning)
        } else {
            let generator = UIImpactFeedbackGenerator(style: .medium)
            generator.impactOccurred()
        }
    }
}

struct iOSMetricRow: View {
    let label: String
    let valueText: String
    let progress: Float
    let color: Color
    let accessibilityText: String
    
    var body: some View {
        VStack(spacing: 4) {
            HStack {
                Text(label)
                    .font(.system(size: 12))
                    .foregroundColor(.gray)
                Spacer()
                Text(valueText)
                    .font(.system(size: 12, weight: .bold))
                    .foregroundColor(color)
            }
            
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    RoundedRectangle(cornerRadius: 3)
                        .fill(Color.white.opacity(0.1))
                        .frame(height: 6)
                    
                    RoundedRectangle(cornerRadius: 3)
                        .fill(color)
                        .frame(width: geo.size.width * CGFloat(progress), height: 6)
                }
            }
            .frame(height: 6)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
    }
}
