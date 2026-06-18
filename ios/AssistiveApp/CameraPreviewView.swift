import SwiftUI
import AVFoundation

/// SwiftUI wrapper for AVCaptureVideoPreviewLayer to display a live camera stream.
struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession?
    
    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.backgroundColor = .black
        
        if let session = session {
            let previewLayer = AVCaptureVideoPreviewLayer(session: session)
            previewLayer.videoGravity = .resizeAspectFill
            previewLayer.frame = view.bounds
            view.layer.addSublayer(previewLayer)
            context.coordinator.previewLayer = previewLayer
        }
        
        return view
    }
    
    func updateUIView(_ uiView: UIView, context: Context) {
        // Keep preview layer sized to the view
        DispatchQueue.main.async {
            context.coordinator.previewLayer?.frame = uiView.bounds
        }
    }
    
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }
    
    class Coordinator {
        var previewLayer: AVCaptureVideoPreviewLayer?
    }
}

import ARKit

struct ARPreviewView: UIViewRepresentable {
    let session: ARSession
    
    func makeUIView(context: Context) -> ARSCNView {
        let sceneView = ARSCNView(frame: .zero)
        sceneView.session = session
        sceneView.backgroundColor = .black
        return sceneView
    }
    
    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}
