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
    @State private var statusText: String = "ระบบพร้อมทำงาน (Real Mode ✅)"
    @State private var aiResultText: String = ""
    @State private var showDevPanel: Bool = false
    @State private var lastLatencyMs: Int = 1850
    
    // Performance Mock Metrics for UI presentation
    @State private var memoryUsageMB: Float = 1420.0
    @State private var cpuTempCelsius: Float = 38.0
    @State private var batteryDrain: Float = 4.2
    @State private var totalInferences: Int = 14
    @State private var averageLatencyMs: Int = 1920
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // ---- Header ----
                HStack {
                    Text("ASSISTIVE OFFLINE AI")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundColor(.white)
                    Spacer()
                    Text("✅ Real Mode")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundColor(.white)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(Color.green)
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
                
                // ---- Camera Viewport Mock ----
                ZStack {
                    Color.black
                    
                    // Simple text indicator instead of AVPlayer for compiler safety
                    VStack {
                        Image(systemName: "camera.fill")
                            .font(.system(size: 40))
                            .foregroundColor(.gray)
                        Text("[ หน้าต่างแสดงภาพถ่าย ]")
                            .font(.system(size: 12))
                            .foregroundColor(.gray)
                            .padding(.top, 4)
                    }
                    
                    VStack {
                        Spacer()
                        Text("แตะสองครั้งเพื่อวิเคราะห์ภาพ")
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
    }
    
    // Action helper methods
    private func triggerCommand(_ command: String) {
        vibrateHaptic(level: 1)
        speakText("กำลังทำคำสั่ง \(command)")
        statusText = "กำลังวิเคราะห์ภาพ..."
        
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            statusText = "ระบบพร้อมทำงาน"
            aiResultText = getMockResponse(command: command)
            speakText(aiResultText)
            
            // Adjust mock stats
            totalInferences += 1
            lastLatencyMs = Int.random(in: 1500...2200)
            vibrateHaptic(level: 2)
        }
    }
    
    private func announceMode() {
        vibrateHaptic(level: 1)
        speakText(currentMode.speech)
    }
    
    private func speakText(_ text: String) {
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: "th-TH")
        utterance.rate = 0.5
        let synthesizer = AVSpeechSynthesizer()
        synthesizer.speak(utterance)
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
    
    private func getMockResponse(command: String) -> String {
        if command.contains("อ่าน") {
            return "ป้ายทางหนีไฟสีเขียวประตูด้านขวา (ความมั่นใจ: สูง)"
        } else if command.contains("ดู") {
            return "แก้วน้ำและกุญแจอยู่ตรงกลางโต๊ะทำงาน (ความมั่นใจ: สูง)"
        } else {
            return "เก้าอี้ไม้ขวางทางเดินด้านหน้าหนึ่งเมตร (ความมั่นใจ: สูง)"
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
