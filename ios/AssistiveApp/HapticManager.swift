import UIKit

class HapticManager {
    static let shared = HapticManager()
    
    private let lightImpactGenerator = UIImpactFeedbackGenerator(style: .light)
    private let impactGenerator = UIImpactFeedbackGenerator(style: .medium)
    private let heavyImpactGenerator = UIImpactFeedbackGenerator(style: .heavy)
    private let notificationGenerator = UINotificationFeedbackGenerator()
    
    private init() {
        // Pre-warm generators on creation
        lightImpactGenerator.prepare()
        impactGenerator.prepare()
        heavyImpactGenerator.prepare()
        notificationGenerator.prepare()
    }
    
    func vibrateTick() {
        DispatchQueue.main.async {
            self.lightImpactGenerator.impactOccurred()
            self.lightImpactGenerator.prepare() // Keep pre-warmed
        }
    }
    
    func vibrateGeneralInfo() {
        DispatchQueue.main.async {
            self.impactGenerator.impactOccurred()
            self.impactGenerator.prepare() // Keep pre-warmed
        }
    }
    
    func vibrateSuccess() {
        DispatchQueue.main.async {
            self.heavyImpactGenerator.impactOccurred()
            self.heavyImpactGenerator.prepare() // Keep pre-warmed
        }
    }
    
    func vibrateWarning() {
        DispatchQueue.main.async {
            self.notificationGenerator.notificationOccurred(.warning)
            self.notificationGenerator.prepare() // Keep pre-warmed
        }
    }
    
    func vibrateDanger() {
        DispatchQueue.main.async {
            self.notificationGenerator.notificationOccurred(.error)
            self.notificationGenerator.prepare() // Keep pre-warmed
        }
    }
}
