# Assistive System for the Visually Impaired (ระบบช่วยเหลือผู้บกพร่องทางการมองเห็น)

ระบบช่วยเหลือผู้บกพร่องทางการมองเห็น (Blind and Low Vision — BLV) ที่ทำงานแบบออฟไลน์เต็มรูปแบบ (Fully Offline) บนอุปกรณ์พกพา โดยใช้แบบจำลองภาษาพหุโหมด Gemma 4 2B (Vision-Language Model) ทำงานร่วมกับระบบถอดความหมายเสียงภาษาไทยออฟไลน์ Sherpa-ONNX และระบบแจ้งเตือนด้วยการสั่นสะเทือนแบบปรับระดับ (Haptic Feedback) เพื่อช่วยยกระดับความปลอดภัยและความเป็นส่วนตัวสูงสุดในการดำเนินชีวิตประจำวัน

---

## 🛠️ Tech Stack

- **Platform**: Android Native (API Level 26+)
- **Programming Language**: Kotlin & Jetpack Compose
- **Camera Library**: Android Jetpack CameraX (ImageAnalysis API)
- **On-Device VLM Runtime**: Google AI Edge LiteRT-LM (TensorFlow Lite successor)
- **VLM Hardware Acceleration**: Vulkan Compute API (GPU acceleration)
- **Offline ASR Framework**: Sherpa-ONNX (Zipformer Thai Model)
- **TTS Engine**: Android System TextToSpeech (Thai voice configuration)
- **Sensors Integration**: IMU (Linear Acceleration & Gyroscope) for movement checking
- **LoRA Fine-Tuning**: PyTorch, Hugging Face Transformers & PEFT (QLoRA 4-bit)

---

## 📋 Prerequisites (ข้อกำหนดเบื้องต้นสำหรับการพัฒนา)

- **OS**: Windows / macOS / Linux
- **Development Tool**: Android Studio (Koala 2024.1.1 or higher recommended)
- **Java Development Kit (JDK)**: JDK 17
- **Gradle Version**: 8.3+
- **Android Target Device**: สมาร์ทโฟน Android ที่มีคุณสมบัติ:
  - หน่วยความจำ RAM ขั้นต่ำ **8 GB**
  - รองรับ GPU Compute API (**Vulkan**)
  - ติดตั้ง Android 8.0 (API Level 26) ขึ้นไป (แนะนำ Android 14+ / API Level 34)

---

## 🚀 Getting Started (เริ่มต้นติดตั้งและใช้งาน)

### 1. โคลนคลังเก็บโค้ด (Clone Project)

```bash
git clone https://github.com/user/assistive-offline-ai.git
cd assistive-offline-ai
```

### 2. เปิดโครงการใน Android Studio

1. เปิด **Android Studio**
2. เลือก **Open** และชี้ไปที่โฟลเดอร์หลักของโครงการ `c:\Users\User\Downloads\Assistive System`
3. รอให้ Gradle โหลดและซิงก์ส่วนประกอบต่างๆ (Gradle Sync) จนเสร็จสิ้น

### 3. การเตรียมไฟล์โมเดลปัญญาประดิษฐ์ (Model Files Preparation)

เนื่องจากตัวโมเดลมีขนาดใหญ่ (~2 GB) จึงต้องโอนย้ายเข้าไปที่โฟลเดอร์ของแอปบนเครื่องทดสอบแยกต่างหาก:

#### ก. โมเดล VLM (Gemma 4 2B)

- เตรียมไฟล์น้ำหนักโมเดล Gemma VLM ในฟอร์แมต LiteRT (`gemma_vlm.litertlm`)
- ส่งไฟล์เข้าไปยังไดเรกทอรีของแอปโดยเปิด Terminal และรันคำสั่ง:

  ```bash
  adb push gemma_vlm.litertlm /data/user/0/com.assistive.system/files/gemma_vlm.litertlm
  ```

#### ข. โมเดลเสียง (Sherpa-ONNX Thai ASR)

- ดาวน์โหลดโมเดลภาษาไทยถอดเสียงออฟไลน์ (Zipformer)
- สร้างโฟลเดอร์ปลายทางและส่งไฟล์เข้ามือถือ:

  ```bash
  adb shell mkdir -p /data/user/0/com.assistive.system/files/sherpa-onnx-thai
  adb push encoder.onnx /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
  adb push decoder.onnx /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
  adb push joiner.onnx /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
  adb push tokens.txt /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
  ```

> [!TIP]
> **ระบบจำลอง (Simulation Mode)**: หากไม่มีไฟล์โมเดลจริงในเครื่อง แอปจะทำงานบน **Mock Mode** อัตโนมัติ เพื่อให้ผู้พัฒนาสามารถกดทดสอบปุ่มจำลองการทำงานของ TTS, UI, และระดับความสั่น (Haptics) ได้ทันทีโดยไม่เกิดอาการแอปค้างหรือเด้งหลุด

---

## 🏗️ Architecture Overview (ภาพรวมสถาปัตยกรรมระบบ)

สถาปัตยกรรมของแอปทำงานร่วมกันในลักษณะ Reactive Pipeline ภายใต้บริการเบื้องหลัง (Foreground Service):

```
                       [ IMU Sensors ] ────┐
                                           │
  [ CameraX ] ──(Frames)──> [ VisionPipeline ] 
                                   │
                               (Trigger)
                                   ▼
  [ Mic Input ] ──(Audio)──> [ AudioPipeline (ASR) ] ──(Command)─┐
                                                                 ▼
[ User UI ] ─────────────> [ AssistiveService ] ───> [ InferenceEngine ]
                                   │                       │
                              (Vibrate/Speak)          (VLM GPU)
                                   ▼                       │
                       [ Haptics & TTS Outputs ] <─────────┘
```

### รายละเอียดโครงสร้างไดเรกทอรีหลัก (Directory Structure)

```
c:/Users/User/Downloads/Assistive System/
├── gradle/
│   └── libs.versions.toml             # แค็ตตาล็อกจัดการเวอร์ชันไลบรารี
├── app/
│   ├── build.gradle.kts               # การตั้งค่าโมดูลและการระบุ Dependency ของแอป
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # การขอสิทธิ์ฮาร์ดแวร์และการลงทะเบียน Service
│       │   └── java/com/assistive/system/
│       │       ├── AssistiveApp.kt    # จุดเริ่มต้น Initialization โกลบอล
│       │       ├── MainActivity.kt    # ส่วนติดต่อผู้ใช้หลัก (Compose UI + Dev Panel)
│       │       ├── audio/
│       │       │   └── AudioPipeline.kt # ควบคุมระบบเสียงพูดและประมวลผลคำสั่งออฟไลน์
│       │       ├── vision/
│       │       │   └── VisionPipeline.kt # จัดการเฟรมกล้อง, จับการขยับตัวเครื่อง และตรวจจับการเปลี่ยนภาพ
│       │       ├── ai/
│       │       │   ├── InferenceEngine.kt # ตัวรันโมเดลภาษา LiteRT-LM บน GPU (Vulkan)
│       │       │   └── OcrPostValidator.kt # รหัสตรวจสอบความถูกต้องของข้อความ OCR ไทยด้วย Regex
│       │       ├── haptic/
│       │       │   └── HapticManager.kt # โปรแกรมสั่นจำแนกตามระดับความอันตราย (Level 1/2/3)
│       │       └── service/
│       │           └── AssistiveService.kt # บริการเบื้องหลัง Foreground Service ควบคุมการไหลข้อมูล
│       └── test/
│           └── java/com/assistive/system/
│               └── AssistiveSystemUnitTest.kt # ชุดทดสอบ Unit Test อัลกอริทึม
└── scripts/
    └── lora_training.py               # สคริปต์ Python ฝึก LoRA Fine-tuning บนคลาวด์/เซิร์ฟเวอร์
```

---

## 📝 Configuration & Environment Variables

ค่ากำหนดระบบในตัวแอปถูกกำหนดไว้ใน `gradle.properties` และสิทธิ์ระดับเครื่องในแอปถูกประกาศไว้ใน `AndroidManifest.xml` ซึ่งมีรายละเอียดความต้องการการเข้าถึงดังนี้:

| Permission | Purpose |
| ---------- | ------- |
| `android.permission.CAMERA` | สำหรับบันทึกเฟรมภาพมาให้โมเดล VLM ประมวลผล |
| `android.permission.RECORD_AUDIO` | สำหรับการฟังคำสั่งเสียง (ASR) ออฟไลน์แบบเรียลไทม์ |
| `android.permission.VIBRATE` | สำหรับส่งสัญญาณ Haptic Feedback แจ้งเตือนคนตาบอด |
| `android.permission.FOREGROUND_SERVICE` | สำหรับคงสภาพการประมวลผลแม้ปิดหน้าจอมือถือ |

---

## 🧪 Testing (วิธีการทดสอบ)

### 1. การรันรหัสทดสอบหน่วย (Automated Unit Tests)

โครงการนี้มี Unit Test สำหรับตรวจสอบการถอดรูปแบบเสียงจำลอง การตรวจสอบความต่างพิกเซลของรูป และการตรวจสอบผล OCR โทรศัพท์/ไปรษณีย์ไทย รันคำสั่งนี้บน IDE Terminal:

```bash
# สำหรับ Windows PowerShell
./gradlew testDebugUnitTest

# สำหรับ Linux/macOS
./gradlew testDebugUnitTest
```

### 2. การทดสอบแบบแมนนวลผ่าน Developer Panel

ในหน้าจอ `MainActivity` คุณสามารถสลับหน้าจอการตรวจสอบได้โดย:

- กดปุ่ม **"แสดงแผงผู้พัฒนา (Show Developer Panel)"**
- จะมีเมนูสำหรับจำลองคำสั่งเสียง:
  - **อ่านข้อความ** (Simulates OCR)
  - **ระบุสิ่งของ** (Simulates Object Detection)
  - **เช็คทางเดิน** (Simulates Obstacle Awareness)
- เมนูสำหรับยิงสัญญานทดสอบการสั่น:
  - **Level 1** (สั่นสั้นหนึ่งครั้งสำหรับข้อความทั่วไป)
  - **Level 2** (สั่นเตือนสองระลอกสำหรับจุดควรระวัง)
  - **Level 3** (สั่นเป็นจังหวะยาวต่อเนื่องเมื่อตรวจพบอันตราย)
- แสดงค่า **JVM Memory Usage** เพื่อดูอัตราการใช้แรมของเครื่องแบบเรียลไทม์

---

## ⚡ LoRA Fine-Tuning Guide (การฝึกโมเดลภาษา)

สคริปต์ [lora_training.py](file:///c:/Users/User/Downloads/Assistive%20System/scripts/lora_training.py) ใช้สำหรับการฝึกฝนโมเดลภาษาบนเซิร์ฟเวอร์ GPU เช่น Google Colab หรือเครื่องเวิร์กสเตชันการ์ดจอแยก

### การรันสคริปต์ฝึกโมเดล

1. เตรียมไฟล์ชุดข้อมูลรูปภาพและไฟล์คำถาม-คำตอบในชื่อ `dataset.json` ตัวอย่างโครงสร้างไฟล์:

   ```json
   [
     {
       "image": "images/test1.jpg",
       "instruction": "ข้างหน้ามีอะไรขวางทางไหม",
       "response": "เก้าอี้พลาสติกสีฟ้าขวางอยู่ข้างหน้า (ความมั่นใจ: สูง)"
     }
   ]
   ```

2. ติดตั้งโมดูลเสริมบน Python:

   ```bash
   pip install torch transformers peft bitsandbytes pillow
   ```

3. สั่งรันฝึกฝน LoRA Adapter:

   ```bash
   python scripts/lora_training.py --dataset_path path/to/dataset.json --output_dir ./gemma_thai_lora
   ```

4. แปลงไฟล์ไปใช้งานในแอปพลิเคชันโดยศึกษาแนวทางที่ระบุไว้ที่ส่วนท้ายของสคริปต์ [lora_training.py](file:///c:/Users/User/Downloads/Assistive%20System/scripts/lora_training.py)

---

## 🛑 Troubleshooting (การแก้ไขปัญหาเบื้องต้น)

### 1. ปัญหา: แอปเด้งออกทันทีหลังเชื่อมต่อกล้องหรือ VLM รัน

- **สาเหตุ**: อาจเกิดจากการเรียกใช้ VLM Model บน GPU โดยที่ยังไม่ได้กำหนดค่า `visionBackend` ใน `EngineConfig` หรือไดรเวอร์ Vulkan ของมือถือรุ่นนั้นมีปัญหา
- **การแก้ไข**: ตรวจเช็คว่ามีการตั้งค่า `.setBackend(Backend.GPU())` และ `.setVisionBackend(Backend.GPU())` แล้วใน [InferenceEngine.kt](file:///c:/Users/User/Downloads/Assistive%20System/app/src/main/java/com/assistive/system/ai/InferenceEngine.kt) หรือเปลี่ยนพารามิเตอร์กลับไปเป็น `Backend.CPU()` เพื่อความเสถียรบนอุปกรณ์ระดับล่าง

### 2. ปัญหา: บิวด์โปรเจกต์ผ่านแล้วแต่เกิดปัญหาไฟล์ native duplicate

- **สาเหตุ**: ไลบรารี LiteRT และ Sherpa-ONNX มีการดึงเอาไฟล์ JNI `libc++_shared.so` ซ้ำซ้อนกัน
- **การแก้ไข**: ตรวจสอบว่าในไฟล์ [app/build.gradle.kts](file:///c:/Users/User/Downloads/Assistive%20System/app/build.gradle.kts) มีบล็อกคำสั่ง `packaging { jniLibs { pickFirsts += "**/libc++_shared.so" } }` เพื่อสั่งให้นำไฟล์แรกที่เจอไปแพ็กกิ้งแทนการสกัดข้อผิดพลาด

---

## 👥 ผู้พัฒนาและลิขสิทธิ์ (License)

โครงงานวิจัยชิ้นนี้จัดทำขึ้นเพื่อยกระดับคุณภาพชีวิตและอำนวยความสะดวกการใช้ชีวิตประจำวันของผู้บกพร่องทางการมองเห็นอย่างแท้จริง
