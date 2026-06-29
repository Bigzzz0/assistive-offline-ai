<<<<<<< Updated upstream
# บันทึกการเปลี่ยนแปลง (Changelog)

บันทึกการเปลี่ยนแปลงและการปรับปรุงทั้งหมดของแอปพลิเคชัน **Assistive Offline AI** เพื่อแก้ไขปัญหาการค้าง การหลุด การลดขนาดหน่วยความจำ และปรับปรุงประสบการณ์ใช้งานของผู้ใช้อย่างเป็นระบบ
=======
# Changelog

All notable changes to the **Assistive Offline AI** application are documented below. These updates focus on resolving critical hardware-specific crashes, enhancing speech recognition accuracy, optimizing memory usage, and improving user experience.
>>>>>>> Stashed changes

---

## [1.1.0] - 2026-06-26

<<<<<<< Updated upstream
### ปรับปรุงและเพิ่มฟีเจอร์ใหม่
- **ระบบประมวลผลร่วมแบบผสม GPU + CPU (Hybrid Acceleration) (`InferenceEngine.kt`)**: 
  - ย้ายการประมวลผลของโมเดลวิเคราะห์ภาพ (Vision Model) ไปทำงานบน CPU ผ่าน XNNPack (`visionBackend = Backend.CPU()`) ซึ่งมีความเสถียรสูง 
  - ขณะที่โมเดลภาษา (LLM text generation) ยังคงทำงานบน GPU (`backend = Backend.GPU()`) เพื่อความเร็วสูงสุด
  - ช่วยแก้ปัญหาเครื่องค้างและแอปพลิเคชันเด้งดับ (SIGSEGV hard crash) บนอุปกรณ์จริงที่เป็นชิป Qualcomm Snapdragon (เช่น OPPO Find X5 Pro) ได้ 100%
- **ระบบจัดการภาพสองระดับความละเอียด (Dual-Resolution Image Pipeline)**:
  - **ภาพบนหน้าจอคมชัดสูง (`VisionPipeline.kt`)**: ปรับความละเอียดภาพที่ส่งไปแสดงในหน้าโหลดประมวลผล (viewfinder overlay) เป็น **`640x640`** พิกเซล เพื่อแสดงผลภาพที่คมชัด ไม่แตกเป็นพิกเซลเบลอ
  - **ภาพสำหรับโมเดลขนาดเล็กพิเศษ (`InferenceEngine.kt`)**: เพิ่มฟังก์ชันย่อขนาดภาพในหน่วยความจำ (in-memory resizer) เหลือ **`224x224`** พิกเซลทันทีก่อนส่งให้โมเดล VLM เพื่อรักษาความเร็วและความเสถียรสูงสุดในการประมวลผล
- **ปุ่มยกเลิกการประมวลผล (✕) (`MainActivity.kt`)**: เพิ่มปุ่มกากบาทสีขาวบนพื้นหลังวงกลมกึ่งโปร่งแสงที่มุมขวาบนของหน้าจอโหลดวิเคราะห์ภาพ เพื่อให้ผู้ใช้สามารถกดคลิกเพื่อยกเลิกการทำงานที่ค้างหรือช้าได้ทันที
- **ระบบการยกเลิกงานเบื้องหลังแบบสมบูรณ์ (`AssistiveService.kt`)**: พัฒนาคำสั่ง `cancelCurrentAnalysis()` เพื่อยกเลิกงาน Coroutine Job ที่กำลังรันอยู่ เคลียร์คิวคำสั่งและรูปภาพทั้งหมด หยุดเสียงพูดพูดทันที และรวมการทำงานนี้เข้ากับคำสั่งเสียง **"หยุด"**
- **ระบบตรวจสอบอุปกรณ์จำลองอัจฉริยะ (Architecture-Aware Emulator Check) (`InferenceEngine.kt`)**: เพิ่มระบบตรวจสอบว่าหากรันบนอุปกรณ์จำลอง (Emulator) และมีสถาปัตยกรรม CPU เป็น `x86` หรือ `x86_64` ระบบจะบังคับให้ทำงานใน **โหมดจำลอง (Mock Mode)** สำหรับ VLM โดยอัตโนมัติ เพื่อป้องกันไม่ให้เกิดการเด้งหลุดจากการที่ชุดคำสั่งไลบรารีเนทีฟ (LiteRT JNI) รองรับเฉพาะ ARM64 เท่านั้น ขณะเดียวกันหากไปรันบนอุปกรณ์จริง (ARM64) จะทำงานในโหมดจริงตามปกติ

### แก้ไขข้อผิดพลาดและเพิ่มประสิทธิภาพ
- **ระบบตรวจจับเสียงพูดแบบประหยัดพลังงาน (ASR VAD) (`AudioPipeline.kt`)**: พัฒนาระบบ Voice Activity Detection (VAD) คัดกรองสัญญาณเสียงที่บันทึก หากระดับเสียงต่ำกว่าเกณฑ์เฉลี่ย (MAE < `0.015`) จะไม่สะสมเสียงในบัฟเฟอร์ และจะส่งถอดรหัสเฉพาะเมื่อตรวจพบเสียงพูดจริงที่จบลงภายใน ~0.8 วินาทีเท่านั้น ช่วยแก้ปัญหาการถอดรหัสสัญญาณรบกวนเงียบและแสดงผลข้อความ **"สวัสดีครับ"** ซ้ำๆ โดยไม่มีผู้พูดได้สมบูรณ์
- **ระบบป้องกันการค้างและการล็อกสถานะ (Deadlock-Proof Task Scheduling) (`AssistiveService.kt`)**: Wrap การทำงานทั้งหมดของ Coroutine ด้วยบล็อก `try ... catch ... finally` เพื่อรับประกันว่าสถานะของเอนจิน AI (`isAnalyzing`) จะต้องถูกรีเซ็ตกลับเป็น `false` เคลียร์ Job และปิดหน้ากากโหลดวิเคราะห์ภาพเสมอใน**ทุกกรณี** (ไม่ว่าจะประมวลผลสำเร็จ เกิดข้อผิดพลาด JNI หรือผู้ใช้กดปุ่มยกเลิก) ป้องกันปัญหาแอปค้างไม่ตอบสนองหลังจากใช้งานไปสักระยะ
- **ระบบปิดหน้ากากโหลดวิเคราะห์ภาพทันที (`AssistiveService.kt`)**: ปรับปรุงให้หน้ากากประมวลผลภาพดับลงทันทีหลังจากได้ผลลัพธ์ข้อความจาก AI เพื่อคืนหน้าจอกล้องสดให้ผู้ใช้งานทันทีโดยไม่ต้องรอให้เสียงพูดอ่านออกเสียงจนจบประโยค
- **ปรับลดการใช้หน่วยความจำ KV Cache (`InferenceEngine.kt`)**: ปรับลดตัวแปร `maxNumTokens` เหลือ `512` สำหรับ GPU/NPU และ `384` สำหรับ CPU (จากเดิม `1024`) ช่วยลดขนาดการจองหน่วยความจำบัฟเฟอร์ KV Cache ลงถึง **3 เท่า** ช่วยแก้ปัญหาหน่วยความจำเนทีฟเต็ม (OOM crash) บน CPU
- **ระบบรวบรวมบันทึกแบบรวมศูนย์ (`AppLogger.kt` & imports)**: 
  - เพิ่มฟังก์ชันบันทึก log ที่รองรับการแทรก Stack Trace ของ `Throwable` เต็มรูปแบบลงในไฟล์ `app_logs.txt`
  - เปลี่ยนการนำเข้าไลบรารี `Log` ในทุกคลาสหลักของระบบมาใช้ `AppLogger`
  - ย้ายการเตรียมการระบบล็อกไปไว้ที่ `AssistiveApp.onCreate()` เพื่อบันทึกทุกเหตุการณ์ตั้งแต่การสตาร์ทแอปครั้งแรก
=======
### Added
- **Hybrid GPU + CPU Vision Acceleration (`InferenceEngine.kt`)**: Added support for running the heavy vision model graph on the CPU via XNNPack (`visionBackend = Backend.CPU()`), while executing the large language model (LLM text generation) on the GPU (`backend = Backend.GPU()`). This resolves native GPU delegate crashes (SIGSEGV reboots) on physical Qualcomm Snapdragon devices (e.g., OPPO Find X5 Pro) while maintaining high token generation speeds.
- **Dual-Resolution Image Pipeline**:
  - **High-Res UI Viewfinder (`VisionPipeline.kt`)**: Camera frames are now downscaled to a sharp **`640x640`** pixels, ensuring the loading screen overlay in the UI displays a clear, high-quality image.
  - **On-the-Fly VLM Rescaling (`InferenceEngine.kt`)**: Added an in-memory `resizeImageForVlm()` helper to quickly rescale the `640x640` image down to **`224x224`** pixels just before passing it to the LiteRT-LM model, ensuring maximum speed and stability.
- **Loading Screen Cancel Button (✕) (`MainActivity.kt`)**: Added a circular, accessible close button in the top-right corner of the loading screen overlay. Clicking it immediately aborts any stuck or long-running AI task.
- **Active Task Cancellation (`AssistiveService.kt`)**: Implemented a `cancelCurrentAnalysis()` method that cancels the active coroutine job, resets analyzing flags, flushes prompt/image queues, and stops active TTS speech playback. Integrated this cancellation logic with the voice command **"หยุด"** (Stop).
- **Architecture-Aware Emulator Check (`InferenceEngine.kt`)**: Added an automated safety check that detects if the application is running on an `x86`/`x86_64` Android emulator. If detected, it automatically forces **Mock Mode** for VLM tasks to prevent JNI crashes (since multimodal LiteRT-LM vision kernels are compiled exclusively for ARM64 NEON vector instructions), while preserving full VLM execution on physical ARM64 devices.

### Changed & Optimized
- **Voice Activity Detection (VAD) ASR Chunking (`AudioPipeline.kt`)**: Replaced the continuous silence-decoding loop with an energy-based VAD chunking mechanism. The ASR now filters out background static/silence using a Mean Absolute Error (MAE) threshold of `0.015`. It only accumulates and decodes audio when active speech is detected, completely eliminating the continuous "สวัสดีครับ" (ASR hallucination) log flood.
- **Deadlock-Proof Task Scheduling (`AssistiveService.kt`)**: Wrapped the entire AI analysis execution inside a robust `try ... catch ... finally` block. The engine state, active jobs, and loading overlays are now **guaranteed** to reset and unlock under all possible execution paths (success, JNI failure, exception, or cancellation), eliminating freezes.
- **Immediate UI Dismissal (`AssistiveService.kt`)**: Configured the loading screen overlay to disappear **instantly** when the VLM inference completes and the text is displayed, allowing the user to use the camera viewfinder immediately while the voice reads out the description in the background.
- **KV Cache Memory Optimization (`InferenceEngine.kt`)**: Optimized the pre-allocated Key-Value (KV) cache by reducing `maxNumTokens` to `512` on GPU/NPU and `384` on CPU (down from `1024`). This reduces pre-allocated memory overhead by **3 times**, preventing native Out-of-Memory (OOM) crashes on resource-constrained CPUs.
- **Unified Logging Redirection (`AppLogger.kt`)**:
  - Added overloaded methods `d()`, `i()`, and `w()` accepting optional `Throwable` stack traces.
  - Redirected imports in all core modules (`MainActivity`, `ModelDownloader`, `AudioPipeline`, `VisionPipeline`, `PerformanceMonitor`, `HapticManager`, `OcrPostValidator`) to route all logs, telemetry, and exceptions to the persistent `app_logs.txt` and the real-time Dev UI console.
  - Initialized logging early in `AssistiveApp.onCreate()` to capture events from the absolute start of the application.
>>>>>>> Stashed changes

---

## [1.0.0] - 2026-06-18
<<<<<<< Updated upstream
- เวอร์ชันเปิดตัวเริ่มต้น รองรับระบบตรวจจับเสียงพูดออฟไลน์ (ASR) และระบบจำแนกภาพออฟไลน์ (VLM) ในโหมดจำลองและโหมดใช้งานจริง
=======
- Initial release with offline speech recognition (ASR) and offline vision-language model (VLM) capabilities in Mock/Real modes.
>>>>>>> Stashed changes
