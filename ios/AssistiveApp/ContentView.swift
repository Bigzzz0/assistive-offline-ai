import SwiftUI
import AVFoundation

enum ActiveMode: String, CaseIterable {
    case read = "📖 อ่านหนังสือ (OCR)"
    case object = "👁️ หาวัตถุบนโต๊ะ"
    case obstacle = "🚧 ตรวจสิ่งกีดขวาง"
    case pointAndSpeak = "👉 ชี้แล้วอ่าน"
    case peopleDetection = "👤 เตือนระยะคน"
    case roomPlan = "🪑 ค้นหาเก้าอี้และประตู"
    
    var command: String {
        switch self {
        case .read: return "อ่าน"
        case .object: return "ดู"
        case .obstacle: return "ข้างหน้า"
        case .pointAndSpeak: return "ชี้"
        case .peopleDetection: return "คน"
        case .roomPlan: return "สแกนห้อง"
        }
    }
    
    var speech: String {
        switch self {
        case .read: return "โหมดอ่านหนังสือ"
        case .object: return "โหมดหาวัตถุ"
        case .obstacle: return "โหมดตรวจจับสิ่งกีดขวาง"
        case .pointAndSpeak: return "โหมดชี้แล้วอ่าน"
        case .peopleDetection: return "โหมดเตือนระยะคน"
        case .roomPlan: return "โหมดค้นหาเก้าอี้และประตู"
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
    @State private var lastModeChangeTime: Date = .distantPast
    private let modeSwitchCooldown: TimeInterval = 1.0
    @State private var statusText: String = "กำลังเริ่มต้นระบบ..."
    @State private var aiResultText: String = ""
    @State private var showDevPanel: Bool = false
    @State private var showModelManager: Bool = false
    @State private var isMockMode: Bool = true
    @State private var lastLatencyMs: Int = 0
    @State private var isProcessing: Bool = false
    
    // Camera permission state
    @State private var cameraAuthorized: Bool = false
    @State private var cameraSessionStarted: Bool = false
    
    @ObservedObject private var downloader = IOSModelDownloader.shared
    @ObservedObject private var logStore = LogStore.shared
    @State private var showDebugConsole: Bool = true
    @State private var volumeObserver: NSKeyValueObservation?
    
    private var statusLabel: String {
        if isMockMode {
            return "ระบบพร้อมทำงาน (โหมดจำลอง)"
        }
        if !InferenceEngine.shared.isReady() {
            return "⚡ กำลังโหลดโมเดล AI..."
        }
        return "ระบบพร้อมทำงาน (ใช้งานจริง)"
    }
    
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
                    Text(isMockMode ? "⚠️ Mock" : "✅ Real Mode")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(isMockMode ? Color.gray : Color.green)
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
                    if cameraAuthorized {
                        if currentMode == .peopleDetection, let arSession = ARDepthPipeline.shared.session {
                            ARPreviewView(session: arSession)
                        } else if currentMode == .roomPlan, let roomSession = RoomPlanManager.shared.session {
                            #if canImport(RoomPlan)
                            ARPreviewView(session: roomSession.arSession)
                            #else
                            Color.black
                            #endif
                        } else if let session = VisionPipeline.shared.session {
                            CameraPreviewView(session: session)
                        } else {
                            Color.black
                        }
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
                        Text(statusLabel)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(isMockMode ? .gray : (!InferenceEngine.shared.isReady() ? .yellow : .green))
                    }
                    Spacer()
                    if lastLatencyMs > 0 {
                        Text("\(lastLatencyMs)ms")
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(isMockMode ? .gray : (!InferenceEngine.shared.isReady() ? .yellow : .green))
                    }
                }
                .padding()
                .frame(maxWidth: .infinity)
                .background(Color(red: 0.07, green: 0.07, blue: 0.07))
                .cornerRadius(12)
                .overlay(
                    RoundedRectangle(cornerRadius: 12)
                        .stroke(isMockMode ? Color.gray : (!InferenceEngine.shared.isReady() ? Color.yellow : Color.green), lineWidth: 2)
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
                    
                    Button(action: {
                        currentMode = .pointAndSpeak
                    }) {
                        Text("👉 ชี้นิ้วแล้วอ่าน (Point & Speak)")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.purple)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มชี้นิ้วแล้วอ่านข้อความที่ปลายนิ้วสัมผัส")
                    
                    Button(action: {
                        currentMode = .peopleDetection
                    }) {
                        Text("👤 เตือนระยะคน (People Alert)")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.pink)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มเตือนระยะห่างคนรอบตัวด้วยเสียงและการสั่น")
                    
                    Button(action: {
                        currentMode = .roomPlan
                    }) {
                        Text("🪑 ค้นหาเก้าอี้และประตู (LiDAR)")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 60)
                            .background(Color.teal)
                            .cornerRadius(12)
                    }
                    .accessibilityLabel("ปุ่มค้นหาเก้าอี้และประตูรอบตัวโดยใช้ไรดาร์สแกน")
                }
                // ---- Dev Panel + Model Manager toggles ----
                HStack(spacing: 8) {
                    Button(action: {
                        showDevPanel.toggle()
                        vibrateHaptic(level: 1)
                    }) {
                        Text(showDevPanel ? "ซ่อน Dev" : "📊 Dev Panel")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.blue)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(RoundedRectangle(cornerRadius: 8).stroke(Color.blue, lineWidth: 1))
                    }
                    
                    Button(action: {
                        showModelManager.toggle()
                        vibrateHaptic(level: 1)
                    }) {
                        Text(showModelManager ? "ซ่อนโมเดล" : "⬇️ จัดการโมเดล")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(.blue)
                            .padding()
                            .frame(maxWidth: .infinity)
                            .background(RoundedRectangle(cornerRadius: 8).stroke(Color.blue, lineWidth: 1))
                    }
                }
                
                // ---- Model Manager Panel ----
                if showModelManager {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("⬇️ จัดการโมเดล AI")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                        
                        let isVlmReady = IOSModelFile.vlm.isDownloaded
                        let vlmSizeMB = Double(IOSModelFile.vlm.downloadedSize) / 1_048_576.0
                        
                        HStack {
                            Text("📦 VLM (Gemma 4 E2B):")
                                .font(.system(size: 13))
                                .foregroundColor(.gray)
                            Spacer()
                            Text(isVlmReady ? String(format: "✅ พร้อม (%.1f MB)", vlmSizeMB) : "❌ ยังไม่ได้โหลด")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(isVlmReady ? .green : .red)
                        }
                        
                        let freeSpace = availableSpaceMB()
                        Text("💾 พื้นที่ว่าง: \(freeSpace) MB")
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                        
                        Divider().background(Color.gray.opacity(0.3))
                        
                        if downloader.isDownloading {
                            VStack(alignment: .leading, spacing: 6) {
                                ProgressView(value: downloader.progress)
                                    .accentColor(.blue)
                                Text(downloader.statusMessage)
                                    .font(.system(size: 11))
                                    .foregroundColor(.gray)
                                
                                Button(action: {
                                    downloader.cancelDownload()
                                    vibrateHaptic(level: 1)
                                }) {
                                    Text("ยกเลิกการดาวน์โหลด")
                                        .font(.system(size: 12, weight: .bold))
                                        .foregroundColor(.red)
                                        .padding(.vertical, 8)
                                        .frame(maxWidth: .infinity)
                                        .background(RoundedRectangle(cornerRadius: 6).stroke(Color.red, lineWidth: 1))
                                }
                            }
                        } else {
                            if !isVlmReady {
                                Button(action: {
                                    downloader.startDownload(.vlm)
                                    vibrateHaptic(level: 1)
                                }) {
                                    Text("⬇️ ดาวน์โหลด Gemma 4 Model (~1.5 GB)")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.white)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 44)
                                        .background(Color.blue)
                                        .cornerRadius(8)
                                }
                            } else {
                                Button(action: {
                                    downloader.deleteModel(.vlm)
                                    isMockMode = InferenceEngine.shared.initialize(force: true) ? InferenceEngine.shared.isMock() : true
                                    vibrateHaptic(level: 1)
                                }) {
                                    Text("🗑️ ลบโมเดล Gemma 4")
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundColor(.white)
                                        .frame(maxWidth: .infinity)
                                        .frame(height: 44)
                                        .background(Color.red)
                                        .cornerRadius(8)
                                }
                            }
                        }
                        
                        if let error = downloader.error {
                            Text(error)
                                .font(.system(size: 11))
                                .foregroundColor(.red)
                        }
                        
                        Button(action: {
                            isMockMode = InferenceEngine.shared.initialize(force: true) ? InferenceEngine.shared.isMock() : true
                            vibrateHaptic(level: 1)
                        }) {
                            Text("🔄 โหลดโมเดลใหม่")
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.white)
                                .frame(maxWidth: .infinity)
                                .frame(height: 44)
                                .background(Color.green)
                                .cornerRadius(8)
                        }
                    }
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(Color(red: 0.1, green: 0.15, blue: 0.27))
                    .cornerRadius(12)
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
                
                // ---- Collapsible Debug Console Panel ----
                VStack(spacing: 8) {
                    HStack(spacing: 8) {
                        Button(action: {
                            withAnimation {
                                showDebugConsole.toggle()
                            }
                        }) {
                            HStack {
                                Text("📋 Debug Console (\(logStore.logs.count) logs)")
                                    .font(.system(size: 13, weight: .bold))
                                Spacer()
                                Image(systemName: showDebugConsole ? "chevron.down" : "chevron.up")
                            }
                            .foregroundColor(.cyan)
                            .padding(.horizontal)
                            .padding(.vertical, 8)
                            .background(Color.cyan.opacity(0.1))
                            .cornerRadius(6)
                        }
                        
                        Button(action: {
                            let allLogs = logStore.logs.joined(separator: "\n")
                            UIPasteboard.general.string = allLogs
                            vibrateHaptic(level: 1)
                            speakText("คัดลอกบันทึกสำเร็จ")
                        }) {
                            HStack {
                                Image(systemName: "doc.on.doc")
                                Text("ก๊อปปี้")
                                    .font(.system(size: 13, weight: .bold))
                            }
                            .foregroundColor(.white)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Color.cyan)
                            .cornerRadius(6)
                        }
                        .accessibilityLabel("คัดลอกบันทึกทั้งหมด")
                    }
                    
                    if showDebugConsole {
                        ScrollViewReader { proxy in
                            ScrollView {
                                VStack(alignment: .leading, spacing: 4) {
                                    if logStore.logs.isEmpty {
                                        Text("[No logs recorded yet]")
                                            .font(.system(size: 10, design: .monospaced))
                                            .foregroundColor(.gray)
                                    } else {
                                        ForEach(logStore.logs, id: \.self) { log in
                                            Text(log)
                                                .font(.system(size: 10, design: .monospaced))
                                                .foregroundColor(.green)
                                                .frame(maxWidth: .infinity, alignment: .leading)
                                                .id(log)
                                        }
                                    }
                                }
                                .padding(6)
                            }
                            .frame(height: 150)
                            .background(Color.black.opacity(0.85))
                            .cornerRadius(8)
                            .overlay(
                                RoundedRectangle(cornerRadius: 8)
                                    .stroke(Color.cyan.opacity(0.3), lineWidth: 1)
                            )
                            .onChange(of: logStore.logs.count) { _ in
                                if let lastLog = logStore.logs.last {
                                    withAnimation {
                                        proxy.scrollTo(lastLog, anchor: .bottom)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.top, 8)
            }
            .padding()
        }
        .background(
            Color.black
                .edgesIgnoringSafeArea(.all)
                .contentShape(Rectangle())
                .onTapGesture(count: 2) {
                    triggerCommand(currentMode.command)
                }
                .onTapGesture(count: 1) {
                    announceMode()
                }
        )
        .gesture(
            DragGesture().onEnded { value in
                guard Date().timeIntervalSince(lastModeChangeTime) >= modeSwitchCooldown else { return }
                if value.translation.width > 100 { // Swipe Right -> previous
                    currentMode = currentMode.previous()
                    announceMode()
                    lastModeChangeTime = Date()
                } else if value.translation.width < -100 { // Swipe Left -> next
                    currentMode = currentMode.next()
                    announceMode()
                    lastModeChangeTime = Date()
                }
            }
        )
        .onAppear {
            requestCameraPermission()
            isMockMode = InferenceEngine.shared.initialize() ? InferenceEngine.shared.isMock() : true
            statusText = "ระบบพร้อมทำงาน"
            handleModeChange(to: currentMode)
            setupVolumeButtonObserver()
        }
        .onDisappear {
            volumeObserver = nil
        }
        .onChange(of: currentMode) { newMode in
            handleModeChange(to: newMode)
        }
        .onChange(of: downloader.isComplete) { isComplete in
            if isComplete {
                isMockMode = InferenceEngine.shared.initialize(force: true) ? InferenceEngine.shared.isMock() : true
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("InferenceEngineStateDidChange"))) { _ in
            isMockMode = InferenceEngine.shared.isMock()
        }
        .onReceive(NotificationCenter.default.publisher(for: NSNotification.Name("AccessibilityPipelineDidUpdate"))) { notification in
            if let userInfo = notification.userInfo {
                if let aiResult = userInfo["aiResult"] as? String {
                    self.aiResultText = aiResult
                }
                if let status = userInfo["status"] as? String {
                    self.statusText = status
                }
            }
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
    
    private func setupVolumeButtonObserver() {
        let audioSession = AVAudioSession.sharedInstance()
        var lastVolume = audioSession.outputVolume
        
        volumeObserver = audioSession.observe(\.outputVolume, options: [.new]) { _, change in
            guard let newVolume = change.newValue else { return }
            if abs(newVolume - lastVolume) > 0.001 {
                lastVolume = newVolume
                DispatchQueue.main.async {
                    vibrateHaptic(level: 1)
                    triggerCommand(currentMode.command)
                }
            }
        }
    }
    
    // MARK: - Actions
    
    private func triggerCommand(_ command: String) {
        if command == "ชี้" {
            speakText("โหมดชี้แล้วอ่านกำลังทำงานอยู่ ยื่นนิ้วชี้ไปหน้ากล้องเพื่อสแกนอ่านข้อความ")
            return
        }
        
        guard !isProcessing else { return }
        
        // If VLM mode is triggered, but model is still loading in the background
        if !isMockMode && !InferenceEngine.shared.isReady() && !command.contains("อ่าน") {
            vibrateHaptic(level: 1)
            speakText("กำลังโหลดโมเดล AI กรุณารอสักครู่")
            aiResultText = "ระบบกำลังดาวน์โหลดและโหลดโมเดล AI เข้าสู่หน่วยความจำ (RAM) กรุณารอสักครู่..."
            return
        }
        
        isProcessing = true
        vibrateHaptic(level: 1)
        let speechPrompt: String
        switch command {
        case "อ่าน": speechPrompt = "กำลังอ่านหนังสือ"
        case "ดู": speechPrompt = "กำลังสำรวจวัตถุบนโต๊ะ"
        case "ข้างหน้า": speechPrompt = "กำลังตรวจจับสิ่งกีดขวางด้านหน้า"
        default: speechPrompt = "กำลังวิเคราะห์ภาพ"
        }
        speakText(speechPrompt)
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
    
    private func handleModeChange(to newMode: ActiveMode) {
        VisionPipeline.shared.activeMode = newMode
        
        // Stop any running special sessions
        ARDepthPipeline.shared.stopSession()
        AudioPipeline.shared.stopTone()
        #if canImport(RoomPlan)
        RoomPlanManager.shared.stopSession()
        #endif
        
        // Reset descriptions for responsive UI feedback
        aiResultText = ""
        statusText = "สลับเป็น\(newMode.speech)"
        
        if newMode == .roomPlan {
            VisionPipeline.shared.stopSession()
            #if canImport(RoomPlan)
            if DeviceCapabilities.supportsLiDAR() {
                RoomPlanManager.shared.startSession()
                // Note: ARDepthPipeline is started inside RoomPlanManager for occupancy detection
            } else {
                speakText("อุปกรณ์นี้ไม่รองรับระบบไลดาร์ สลับเป็นโหมดจำลองสิ่งกีดขวาง")
                aiResultText = "อุปกรณ์ไม่รองรับ LiDAR จะทำงานในโหมดจำลองสิ่งกีดขวางแบบออฟไลน์"
            }
            #else
            speakText("ระบบปฏิบัติการนี้ไม่รองรับรูมแพลน สลับเป็นโหมดจำลองสิ่งกีดขวาง")
            aiResultText = "ไม่รองรับ RoomPlan ในเวอร์ชันนี้ จะทำงานในโหมดจำลองสิ่งกีดขวางแบบออฟไลน์"
            #endif
        } else if newMode == .peopleDetection {
            VisionPipeline.shared.stopSession()
            ARDepthPipeline.shared.startSession()
        } else {
            if !(VisionPipeline.shared.session?.isRunning ?? false) {
                VisionPipeline.shared.startSession()
            }
        }
        
        announceMode()
    }

    private func announceMode() {
        vibrateHaptic(level: 1)
        speakText(currentMode.speech)
    }
    
    private func speakText(_ text: String) {
        // Strip out any technical confidence labels like "(ความมั่นใจ: สูง)" to make it clean for voice feedback
        let cleanText = text
            .replacingOccurrences(of: "\\(ความมั่นใจ:\\s*[^\\)]+\\)", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        AudioPipeline.shared.speak(cleanText)
    }
    
    private func availableSpaceMB() -> Int64 {
        let path = NSSearchPathForDirectoriesInDomains(.documentDirectory, .userDomainMask, true).first ?? NSHomeDirectory()
        do {
            let systemAttributes = try FileManager.default.attributesOfFileSystem(forPath: path)
            if let freeSpace = systemAttributes[.systemFreeSize] as? Int64 {
                return freeSpace / 1_048_576 // Convert to MB
            }
        } catch {}
        return 0
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
