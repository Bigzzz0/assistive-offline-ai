import UIKit

class HapticManager {
    static let shared = HapticManager()
    
    private let impactGenerator = UIImpactFeedbackGenerator(style: .medium)
    private let notificationGenerator = UINotificationFeedbackGenerator()
    
    func vibrateGeneralInfo() {
        DispatchQueue.main.async {
            self.impactGenerator.prepare()
            self.impactGenerator.impactOccurred()
        }
    }
    
    func vibrateWarning() {
        DispatchQueue.main.async {
            self.notificationGenerator.prepare()
            self.notificationGenerator.notificationOccurred(.warning)
        }
    }
    
    func vibrateDanger() {
        DispatchQueue.main.async {
            self.notificationGenerator.prepare()
            self.notificationGenerator.notificationOccurred(.error)
        }
    }
}
