<div align="center">

<img src="https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen?style=for-the-badge&logo=android&logoColor=white" />
<img src="https://img.shields.io/badge/Language-Kotlin-purple?style=for-the-badge&logo=kotlin&logoColor=white" />
<img src="https://img.shields.io/badge/AI%20Model-Gemma%204%202B%20VLM-blue?style=for-the-badge&logo=google&logoColor=white" />
<img src="https://img.shields.io/badge/Inference-LiteRT--LM%20%7C%20Vulkan-orange?style=for-the-badge" />
<img src="https://img.shields.io/badge/Mode-100%25%20Offline-red?style=for-the-badge&logo=lock&logoColor=white" />
<img src="https://img.shields.io/badge/License-Research%20%2F%20Academic-lightgrey?style=for-the-badge" />

<br/><br/>

# 🦯 Assistive Offline AI
### ระบบช่วยเหลือผู้บกพร่องทางการมองเห็น — ทำงานออฟไลน์เต็มรูปแบบ

*A fully offline, privacy-first AI assistant for the Blind and Low Vision (BLV) community,*
*powered by on-device multimodal AI — no internet, no cloud, no compromise.*

<br/>

[📖 Project Proposal](./ProjectProposal.md) · [🚀 Quick Start](#-getting-started) · [🏗️ Architecture](#️-architecture-overview) · [🧪 Testing](#-testing) · [🔧 Troubleshooting](#-troubleshooting)

</div>

---

## 🌟 Overview

**Assistive Offline AI** is an Android application built for the Thai Blind and Low Vision (BLV) community that delivers real-time, AI-powered scene understanding — **entirely on-device**. No data ever leaves the phone.

The system combines three core technologies into a seamless assistive pipeline:

| Component | Technology | Purpose |
|---|---|---|
| 👁️ **Vision** | Gemma 4 2B VLM via LiteRT-LM | อ่านสภาพแวดล้อม, ระบุสิ่งกีดขวาง, อ่าน OCR ภาษาไทย |
| 🎙️ **Voice Commands** | Sherpa-ONNX (Zipformer Thai) | รับคำสั่งเสียงออฟไลน์ไม่ต้องต่ออินเทอร์เน็ต |
| 📳 **Haptic Alerts** | Android Vibrator API (3 Levels) | แจ้งเตือนด้วยการสั่นสะเทือนตามระดับความอันตราย |

> **Privacy First** — ข้อมูลภาพ เสียง และตำแหน่งของผู้ใช้ทุกอย่างประมวลผลในเครื่องเท่านั้น ไม่มีการส่งออกสู่ภายนอก

---

## ✨ Key Features

- 🔍 **Real-time Object Detection** — ระบุสิ่งกีดขวาง วัตถุ และบุคคลผ่านกล้องหลังแบบ real-time
- 📖 **Thai OCR** — อ่านข้อความภาษาไทย-อังกฤษ (ป้าย, เมนู, ป้ายยา) ด้วยความแม่นยำสูง
- 🧭 **Obstacle Awareness** — ตรวจจับอุปสรรคพร้อม IMU เพื่อลดการ inference ขณะผู้ใช้หยุดนิ่ง
- 🔊 **Offline Thai TTS** — ตอบกลับด้วยเสียงภาษาไทยแม้ไม่มีอินเทอร์เน็ต
- ⚡ **GPU Accelerated** — รันโมเดลบน Vulkan GPU ให้ผล inference ภายใต้ 3 วินาที
- 🎛️ **Developer Panel** — แผงทดสอบในตัวสำหรับ simulate การทำงานทุก pipeline
- 🔬 **LoRA Fine-Tuning** — สคริปต์ฝึกโมเดลเพิ่มเติมเฉพาะทางภาษาไทย (QLoRA 4-bit)
- 🔒 **Simulation / Mock Mode** — แอปทำงานได้แม้ยังไม่มีไฟล์โมเดล AI จริง

---

## 🛠️ Tech Stack

```
┌─────────────────────────────────────────────────────────────────┐
│                     ASSISTIVE OFFLINE AI                        │
├────────────────────────┬────────────────────────────────────────┤
│  📱 Platform           │  Android Native (API 26+, Kotlin)      │
│  🖼️ UI Framework       │  Jetpack Compose                       │
│  📷 Camera             │  Android Jetpack CameraX               │
│  🤖 VLM Runtime        │  Google AI Edge LiteRT-LM              │
│  ⚡ GPU Backend        │  Vulkan Compute API                    │
│  🎙️ Offline ASR        │  Sherpa-ONNX (Zipformer Thai)          │
│  🔊 TTS Engine         │  Android System TextToSpeech (Thai)    │
│  📡 IMU Sensors        │  Linear Acceleration + Gyroscope       │
│  🧠 Fine-Tuning        │  PyTorch + HuggingFace PEFT (QLoRA)    │
└────────────────────────┴────────────────────────────────────────┘
```

---

## 📋 Prerequisites

### Development Machine
| Requirement | Minimum | Recommended |
|---|---|---|
| OS | Windows / macOS / Linux | Any |
| IDE | Android Studio Koala 2024.1.1+ | Latest Stable |
| JDK | JDK 17 | JDK 17 |
| Gradle | 8.3+ | 8.3+ |

### Target Android Device
| Requirement | Specification |
|---|---|
| Android Version | 8.0 (API 26+) minimum · **Android 14+ (API 34) recommended** |
| RAM | **8 GB minimum** (for Gemma 4 2B model) |
| GPU | Must support **Vulkan Compute API** |
| Storage | ~3 GB free (for model files) |

---

## 🚀 Getting Started

### Step 1 — Clone the Repository

```bash
git clone https://github.com/Bigzzz0/assistive-offline-ai.git
cd assistive-offline-ai
```

### Step 2 — Open in Android Studio

1. เปิด **Android Studio**
2. เลือก **File → Open** แล้วชี้ไปที่โฟลเดอร์ที่โคลนมา
3. รอให้ **Gradle Sync** ทำงานเสร็จสมบูรณ์ (อาจใช้เวลา 2–5 นาทีครั้งแรก)

### Step 3 — Prepare AI Model Files

เนื่องจากไฟล์โมเดลมีขนาดใหญ่ (~2 GB+) ต้องโอนย้ายเข้าเครื่องทดสอบแยกต่างหาก:

#### 🤖 VLM Model — Gemma 4 2B

```bash
adb push gemma_vlm.litertlm \
  /data/user/0/com.assistive.system/files/gemma_vlm.litertlm
```

#### 🎙️ ASR Model — Sherpa-ONNX Thai

```bash
# สร้างโฟลเดอร์ปลายทาง
adb shell mkdir -p /data/user/0/com.assistive.system/files/sherpa-onnx-thai

# โอนย้ายไฟล์โมเดลทั้งหมด
adb push encoder.onnx  /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
adb push decoder.onnx  /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
adb push joiner.onnx   /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
adb push tokens.txt    /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
```

> [!TIP]
> **ไม่มีไฟล์โมเดล?** ไม่เป็นไร — แอปมี **Mock Mode** ในตัว ที่จำลองการทำงานทุก pipeline ให้ทดสอบ UI, TTS, และ Haptics ได้ทันทีโดยไม่แอปค้างหรือ crash

### Step 4 — Build & Run

```bash
# สั่ง build และรันบนเครื่องที่เชื่อมต่อ
./gradlew installDebug
```

หรือกดปุ่ม **▶ Run** ใน Android Studio ได้เลย

---

## 🏗️ Architecture Overview

ระบบทำงานเป็น Reactive Pipeline ภายใต้ Foreground Service เพื่อให้ทำงานต่อเนื่องแม้ปิดหน้าจอ:

```
┌─────────────────────────────────────────────────────────────────┐
│                        INPUT LAYER                              │
│                                                                 │
│  [ CameraX Frames ]    [ Microphone ]    [ IMU Sensors ]        │
└──────────┬─────────────────┬─────────────────┬─────────────────┘
           │                 │                 │
           ▼                 ▼                 │
┌─────────────────┐  ┌──────────────────┐     │
│ VisionPipeline  │  │  AudioPipeline   │     │
│                 │  │  (Sherpa-ONNX    │     │
│ • Change Detect │  │   Thai ASR)      │     │
│ • Frame Throttle│  │ • Voice Command  │     │
│ • IMU Gating ◀──┼──┼──────────────────┼─────┘
└────────┬────────┘  └────────┬─────────┘
         │                   │
         └─────────┬─────────┘
                   │  (Trigger + Command)
                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                    AssistiveService                             │
│              (Foreground Service Orchestrator)                  │
└──────────────────────────┬──────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │    InferenceEngine     │
              │   Gemma 4 2B VLM       │
              │   LiteRT-LM | Vulkan   │
              └────────────┬───────────┘
                           │
           ┌───────────────┴───────────────┐
           ▼                               ▼
  ┌─────────────────┐            ┌──────────────────┐
  │   TTS Output    │            │  HapticManager   │
  │  (Thai Voice)   │            │  Level 1 / 2 / 3 │
  └─────────────────┘            └──────────────────┘
```

### Haptic Alert Levels

| Level | Pattern | Trigger Condition |
|---|---|---|
| 🟢 **Level 1** | สั่นสั้น 1 ครั้ง | ข้อมูลทั่วไป, OCR, คำตอบปกติ |
| 🟡 **Level 2** | สั่น 2 ระลอก | พบวัตถุใกล้, จุดที่ควรระวัง |
| 🔴 **Level 3** | สั่นยาวต่อเนื่อง | อันตรายทันที, สิ่งกีดขวางหน้าทาง |

---

## 📁 Project Structure

```
assistive-offline-ai/
│
├── 📄 build.gradle.kts              # Root Gradle build config
├── 📄 settings.gradle.kts           # Module configuration
├── 📄 gradle.properties             # Build JVM flags & AndroidX
│
├── 📂 gradle/
│   └── libs.versions.toml           # Version catalog (deps & plugins)
│
├── 📂 app/
│   ├── 📄 build.gradle.kts          # App module dependencies & build rules
│   └── 📂 src/main/
│       ├── 📄 AndroidManifest.xml   # Permissions & Foreground Service declaration
│       └── 📂 java/com/assistive/system/
│           │
│           ├── 🔷 AssistiveApp.kt           # Global Application init
│           ├── 🔷 MainActivity.kt           # Main UI (Compose) + Dev Panel
│           │
│           ├── 📂 audio/
│           │   └── 🔷 AudioPipeline.kt      # Sherpa-ONNX ASR + voice command parsing
│           │
│           ├── 📂 vision/
│           │   └── 🔷 VisionPipeline.kt     # CameraX frames, IMU gating, change detection
│           │
│           ├── 📂 ai/
│           │   ├── 🔷 InferenceEngine.kt    # LiteRT-LM VLM runner (GPU/Vulkan)
│           │   └── 🔷 OcrPostValidator.kt   # Thai OCR regex post-processing
│           │
│           ├── 📂 haptic/
│           │   └── 🔷 HapticManager.kt      # 3-level haptic feedback controller
│           │
│           └── 📂 service/
│               └── 🔷 AssistiveService.kt   # Foreground Service — pipeline orchestrator
│
└── 📂 scripts/
    └── 🐍 lora_training.py          # Python LoRA fine-tuning script (QLoRA 4-bit)
```

---

## ⚙️ App Permissions

| Permission | ความจำเป็น |
|---|---|
| `CAMERA` | บันทึกเฟรมภาพให้โมเดล VLM ประมวลผลแบบ real-time |
| `RECORD_AUDIO` | รับคำสั่งเสียงผ่าน Sherpa-ONNX ASR ออฟไลน์ |
| `VIBRATE` | ส่งสัญญาณ Haptic Feedback 3 ระดับ |
| `FOREGROUND_SERVICE` | ให้ระบบทำงานต่อเนื่องเมื่อปิดหน้าจอมือถือ |
| `POST_NOTIFICATIONS` | แสดงการแจ้งเตือน Foreground Service (Android 13+) |

---

## 🧪 Testing

### Automated Unit Tests

โครงการมี Unit Test ครอบคลุม: ตรรกะ IMU Gating, Pixel Diff Change Detection, OCR Regex Validator

```bash
# Windows PowerShell / macOS / Linux
./gradlew testDebugUnitTest
```

### Manual Testing via Developer Panel

เปิดแผงผู้พัฒนาในแอป: กดปุ่ม **"แสดงแผงผู้พัฒนา"** ใน `MainActivity`

**Voice Command Simulation:**
- 🔘 **อ่านข้อความ** — จำลอง OCR pipeline
- 🔘 **ระบุสิ่งของ** — จำลอง Object Detection
- 🔘 **เช็คทางเดิน** — จำลอง Obstacle Awareness

**Haptic Testing:**
- 📳 **Level 1** · **Level 2** · **Level 3** — ทดสอบรูปแบบการสั่นทั้ง 3 ระดับ

**System Monitoring:**
- 📊 แสดง **JVM Memory Usage** แบบ real-time

---

## 🔬 LoRA Fine-Tuning Guide

สคริปต์ [`scripts/lora_training.py`](./scripts/lora_training.py) ใช้ฝึกโมเดล Gemma 4 2B ให้ตอบสนองภาษาไทยได้ดีขึ้นบน Google Colab / GPU Workstation

### 1. เตรียมชุดข้อมูล (Dataset Format)

```json
[
  {
    "image": "images/scene_001.jpg",
    "instruction": "ข้างหน้ามีอะไรขวางทางไหม",
    "response": "มีเก้าอี้พลาสติกสีฟ้าขวางอยู่ข้างหน้า ระยะห่างประมาณ 1 เมตร (ความมั่นใจ: สูง)"
  }
]
```

### 2. ติดตั้ง Python Dependencies

```bash
pip install torch transformers peft bitsandbytes pillow
```

### 3. รัน Training Script

```bash
python scripts/lora_training.py \
  --dataset_path path/to/dataset.json \
  --output_dir ./gemma_thai_lora
```

### 4. โอนย้าย LoRA Adapter ไปยังแอป

```bash
# หลังฝึกเสร็จ โอน adapter เข้าเครื่อง Android
adb push gemma_thai_lora/ \
  /data/user/0/com.assistive.system/files/lora_adapter/
```

---

## 🔧 Troubleshooting

<details>
<summary><b>❌ แอปเด้งออกหลังเชื่อมต่อกล้องหรือโหลด VLM</b></summary>

**สาเหตุ:** Vulkan backend ไม่รองรับบนเครื่องนั้น หรือกำหนดค่า EngineConfig ไม่ครบถ้วน

**การแก้ไข:** ใน [`InferenceEngine.kt`](./app/src/main/java/com/assistive/system/ai/InferenceEngine.kt) ตรวจสอบว่ามีการตั้งค่าทั้งสองบรรทัดนี้:
```kotlin
.setBackend(Backend.GPU())
.setVisionBackend(Backend.GPU())
```
หากเครื่องไม่รองรับ Vulkan ให้เปลี่ยนเป็น `Backend.CPU()` ชั่วคราว

</details>

<details>
<summary><b>❌ Build สำเร็จแต่เกิด duplicate native library error</b></summary>

**สาเหตุ:** LiteRT-LM และ Sherpa-ONNX ดึงไฟล์ `libc++_shared.so` ซ้ำซ้อนกัน

**การแก้ไข:** ตรวจสอบว่า [`app/build.gradle.kts`](./app/build.gradle.kts) มีบล็อกนี้:
```kotlin
packaging {
    jniLibs {
        pickFirsts += "**/libc++_shared.so"
    }
}
```

</details>

<details>
<summary><b>❌ Gradle Sync ล้มเหลว — ไม่พบ LiteRT-LM artifact</b></summary>

**สาเหตุ:** Maven repository ของ Google AI Edge ต้องการการระบุในส่วน `dependencyResolutionManagement`

**การแก้ไข:** ตรวจสอบ [`settings.gradle.kts`](./settings.gradle.kts) ว่ามี:
```kotlin
maven { url = uri("https://maven.google.com") }
maven { url = uri("https://storage.googleapis.com/litertlm-releases/") }
```

</details>

<details>
<summary><b>❌ TTS ภาษาไทยไม่ได้ยินเสียง</b></summary>

**สาเหตุ:** เครื่อง Android ไม่มีชุดข้อมูลเสียงภาษาไทย (Thai TTS Language Pack) ติดตั้ง

**การแก้ไข:** ไปที่ **Settings → Accessibility → Text-to-Speech** แล้วดาวน์โหลด **ภาษาไทย (Thai)**

</details>

---

## 🗺️ Roadmap

- [x] Phase 1 — Android Project Setup & Gradle Configuration
- [x] Phase 2 — VisionPipeline + IMU Sensor Gating
- [x] Phase 3 — AudioPipeline (Sherpa-ONNX Thai ASR)
- [x] Phase 4 — InferenceEngine (LiteRT-LM + Vulkan GPU)
- [x] Phase 5 — HapticManager (3-Level Vibration)
- [x] Phase 6 — AssistiveService Foreground Orchestration
- [x] Phase 7 — MainActivity Compose UI + Developer Panel
- [x] Phase 8 — LoRA Fine-Tuning Script (QLoRA 4-bit)
- [ ] Phase 9 — LoRA Adapter loading in LiteRT-LM runtime
- [ ] Phase 10 — End-to-end field testing with BLV users
- [ ] Phase 11 — iOS support evaluation (Swift / CoreML)

---

## 👥 About This Project

โครงงานวิจัยชิ้นนี้จัดทำขึ้นเพื่อยกระดับคุณภาพชีวิตและอำนวยความสะดวกการใช้ชีวิตประจำวันของผู้บกพร่องทางการมองเห็น (BLV) ในประเทศไทยอย่างแท้จริง โดยใช้เทคโนโลยี AI สมัยใหม่ที่ประมวลผลทุกอย่างในเครื่อง เพื่อปกป้องความเป็นส่วนตัวสูงสุดและรองรับการใช้งานในพื้นที่ที่ไม่มีอินเทอร์เน็ต

> *"Technology should empower everyone — including those who see the world differently."*

---

<div align="center">

**Made with ❤️ for the Blind & Low Vision Community**

[![GitHub](https://img.shields.io/badge/GitHub-Bigzzz0-181717?style=flat-square&logo=github)](https://github.com/Bigzzz0/assistive-offline-ai)

</div>
