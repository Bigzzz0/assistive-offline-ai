<div align="center">

<img src="https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20iOS%2016.0%2B-brightgreen?style=for-the-badge&logo=android&logoColor=white" />
<img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20Swift-purple?style=for-the-badge&logo=swift&logoColor=white" />
<img src="https://img.shields.io/badge/AI%20Model-Gemma%204%202B%20VLM-blue?style=for-the-badge&logo=google&logoColor=white" />
<img src="https://img.shields.io/badge/Inference-LiteRT--LM-orange?style=for-the-badge" />
<img src="https://img.shields.io/badge/Mode-100%25%20Offline-red?style=for-the-badge&logo=lock&logoColor=white" />
<img src="https://img.shields.io/badge/License-Research%20%2F%20Academic-lightgrey?style=for-the-badge" />

<br/><br/>

# 🦯 Assistive Offline AI
### ระบบช่วยเหลือผู้บกพร่องทางการมองเห็น — ทำงานออฟไลน์เต็มรูปแบบ

*A fully offline, privacy-first AI assistant for the Blind and Low Vision (BLV) community,*  
*powered by on-device multimodal AI — no internet, no cloud, no compromise.*

<br/>

[📖 Project Proposal](./ProjectProposal.md) · [🚀 Getting Started](#-getting-started) · [🏗️ Architecture](#️-architecture-overview) · [🧪 Testing](#-testing) · [🔧 Troubleshooting](#-troubleshooting)

</div>

---

## 🌟 Overview

**Assistive Offline AI** คือแอปพลิเคชันระบบช่วยเหลือผู้บกพร่องทางการมองเห็น (BLV) ในประเทศไทยที่รองรับทั้งระบบปฏิบัติการ **Android** และ **iOS** เพื่ออธิบายภาพและแจ้งเตือนสภาพแวดล้อมรอบตัวแบบ Real-time โดยประมวลผลผ่านโมเดลเอไอขนาดใหญ่ **On-Device (ออฟไลน์ 100%)** เพื่อความปลอดภัยสูงสุดของข้อมูลส่วนบุคคลและไม่ต้องพึ่งพาอินเทอร์เน็ต

แอปพลิเคชันรวม 3 ระบบการทำงานหลักเข้าด้วยกัน:
* **👁️ Vision**: ประมวลผลภาพกล้องด้วยโมเดล Gemma 4 2B VLM (ผ่านเฟรมเวิร์ก LiteRT-LM) และ OCR ออฟไลน์เพื่อวิเคราะห์วัตถุ สิ่งกีดขวาง และข้อความ
* **🎙️ Interaction**: ระบบโต้ตอบคำสั่งเสียง (ASR บน Android และ Gesture สัมผัสบน iOS) พร้อมตอบกลับด้วยเสียงพูดภาษาไทย (TTS)
* **📳 Haptic alerts**: แจ้งเตือนความปลอดภัยด้วยการสั่นสะเทือนแบบต่างระดับ (3 ระดับการสั่น)

---

## ✨ Key Features

* **🔍 Real-time Object Description** — ระบุและอธิบายสภาพแวดล้อม สิ่งของบนโต๊ะ ด้วยโมเดล Gemma 4 VLM
* **📖 Offline Thai OCR** — อ่านตัวอักษรภาษาไทยและอังกฤษออฟไลน์ผ่านกล้องโดยไม่พึ่งโมเดลหนัก (ใช้ Apple Vision บน iOS และ Google ML Kit บน Android)
* **🚧 Obstacle Awareness** — สแกนสิ่งกีดขวางข้างหน้าพร้อมแจ้งความปลอดภัยแยกโหมด
* **⚡ Hybrid GPU Accelerated** — รันส่วนโมเดลภาษาของ VLM บน GPU (Vulkan บน Android และ Metal บน iOS) ร่วมกับ CPU ในส่วนรูปภาพ เพื่อลดความหน่วงในการตอบสนองให้ต่ำกว่า 3 วินาที
* **🧹 Memory Optimizations & One-Shot Session Reset** — iOS รองรับการสร้าง Session ใหม่ทุกการเรียกสแกนเพื่อเคลียร์แคชโทเค็นภาพ ป้องกันอาการแอปเด้งจากหน่วยความจำล้น (OOM)
* **📋 Debug Console & Copy Logs** — แผงรายงานดีบักเรียลไทม์บนหน้าแอป พร้อมปุ่มก๊อปปี้คลิปบอร์ดในคลิกเดียวเพื่อวิเคราะห์ปัญหาง่ายขึ้น
* **🔒 Simulation / Mock Mode** — โหมดจำลองที่ช่วยให้แอปทำงานจำลองได้แม้ยังไม่พร้อมดาวน์โหลดโมเดลจริงในเครื่อง

---

## 🛠️ Tech Stack

### Android Component
* **Language & UI**: Kotlin, Jetpack Compose, CameraX
* **ASR Engine**: Sherpa-ONNX (Zipformer Thai)
* **VLM Engine**: Google AI Edge LiteRT-LM (Vulkan Compute API)
* **TTS Engine**: Android System TextToSpeech (Thai)

### iOS Component
* **Language & UI**: Swift, SwiftUI, AVFoundation
* **OCR Engine**: Apple Vision Framework (Thai & English)
* **VLM Engine**: Google AI Edge LiteRTLM (Metal Shader Language)
* **TTS Engine**: AVSpeechSynthesizer (Thai Voice Pack)
* **Entitlement**: `com.apple.developer.kernel.increased-memory-limit` (เพิ่มโควตา RAM สูงสุดเป็น 3GB สำหรับโมเดลขนาดใหญ่)

---

## 📋 Prerequisites

### target device
* **Android**: API 26+ (Android 8.0+) มี RAM ขั้นต่ำ 8 GB และรองรับ Vulkan GPU
* **iOS**: iOS 16.0+ (ชิป A12 Bionic ขึ้นไป), RAM 8 GB ขึ้นไป (เช่น iPhone 15 Pro, iPhone 16 ซีรีส์)

### Development Machine
* **Android Development**: Android Studio Koala+, JDK 17+
* **iOS Development**: macOS, Xcode 15+, **XcodeGen**

---

## 🚀 Getting Started

### 🤖 Android Setup

1. **โคลนและเปิดโปรเจกต์** ใน Android Studio
2. **โอนย้ายโมเดล** ไปยังโฟลเดอร์แอปปลายทาง:
   ```bash
   # โอนโมเดล Gemma 4 VLM
   adb push gemma_vlm.litertlm /data/user/0/com.assistive.system/files/gemma_vlm.litertlm
   
   # โอนโมเดลระบบสั่งเสียง Sherpa-ONNX
   adb shell mkdir -p /data/user/0/com.assistive.system/files/sherpa-onnx-thai
   adb push encoder.onnx decoder.onnx joiner.onnx tokens.txt /data/user/0/com.assistive.system/files/sherpa-onnx-thai/
   ```
3. กดปุ่ม **Run** บนอุปกรณ์ทดสอบจริง

---

### 🍏 iOS Setup (XcodeGen)

การจัดการโครงสร้างโปรเจกต์ iOS ในโปรเจกต์นี้ใช้ XcodeGen เพื่อความง่ายในการรักษา configuration:

1. **ติดตั้ง XcodeGen** (ในเครื่อง macOS):
   ```bash
   brew install xcodegen
   ```
2. **สร้างโปรเจกต์ Xcode**:
   ```bash
   cd ios
   xcodegen generate
   ```
3. **เปิดโปรเจกต์ใน Xcode**:
   ```bash
   open AssistiveApp.xcodeproj
   ```
4. **การเตรียมไฟล์โมเดล AI**:
   * **วิธีที่ 1 (แนะนำ)**: สั่งรันแอปเปล่าบนไอโฟน เปิดเมนู **"จัดการโมเดล" (Model Manager)** ในแอป แล้วแตะ **"ดาวน์โหลดโมเดล"** ระบบจะโหลดไฟล์ `gemma-4-E2B-it.litertlm` ขนาด ~1.5 GB ตรงจาก Hugging Face สู่ระบบทันที
   * **วิธีที่ 2**: เชื่อมต่อไอโฟนผ่าน iTunes หรือ Finder (File Sharing) แล้วนำไฟล์ `gemma-4-E2B-it.litertlm` ไปใส่ใน Documents ของแอป

---

## 📁 Project Structure

```
assistive-offline-ai/
│
├── 📂 app/                           # โมดูลฝั่ง Android (Kotlin, Jetpack Compose)
│   └── src/main/java/com/assistive/system/
│       ├── 📂 ai/                     # LiteRT-LM VLM integration (Android)
│       └── 📂 audio/                  # Offline Thai ASR (Sherpa-ONNX)
│
├── 📂 ios/                           # โครงสร้างฝั่ง iOS (Swift, SwiftUI)
│   ├── 📄 project.yml                # โครงสร้างโปรเจกต์สำหรับ XcodeGen
│   └── 📂 AssistiveApp/
│       ├── 🔷 AssistiveApp.entitlements # ไฟล์สิทธิ์การขอสิทธิ์หน่วยความจำเพิ่ม (RAM Limit)
│       ├── 🔷 ContentView.swift      # หน้าจอหลัก, ท่าทางปัดสลับโหมด และ Debug Console
│       ├── 🔷 InferenceEngine.swift  # คลาสประมวลผล VLM (Metal GPU + CPU vision)
│       ├── 🔷 VisionPipeline.swift   # AVFoundation Camera preview
│       ├── 🔷 ModelDownloader.swift  # ระบบดาวน์โหลดโมเดล Gemma จาก HuggingFace
│       └── 🔷 HapticManager.swift     # ระบบสั่น CoreHaptics 3 ระดับ
│
└── 📂 scripts/
    └── 🐍 lora_training.py           # สคริปต์ Python สำหรับทำ LoRA Fine-Tuning
```

---

## 🔧 Troubleshooting

<details>
<summary><b>❌ [iOS] แอปเด้งออกทันทีหลังโหลด VLM หรือสแกนรอบแรกเสร็จ</b></summary>

* **สาเหตุ**: การประมวลผลโมเดล VLM ขนาดใหญ่บน iOS จะใช้ RAM สูง หากไม่ได้ผูกสิทธิ์สิทธิพิเศษขอข้ามขีดจำกัดหน่วยความจำ แอปจะถูกระบบ iOS สั่งฆ่าโดยอัตโนมัติ
* **การแก้ไข**: ตรวจสอบว่าโปรเจกต์มีไฟล์ `AssistiveApp.entitlements` และผูกอยู่ใน `project.yml` หรือ Xcode Build Settings เรียบร้อยแล้ว สิทธิ์นี้จะอนุญาตให้แอปใช้ RAM ได้สูงสุด 3GB:
  ```xml
  <key>com.apple.developer.kernel.increased-memory-limit</key>
  <true/>
  ```

</details>

<details>
<summary><b>❌ [iOS] เกิดความผิดพลาดในการเริ่มทำงานของโมเดลบน GPU (Metal)</b></summary>

* **สาเหตุ**: ตัวประมวลผลภาพ (Vision delegate) ของเฟรมเวิร์ก LiteRT-LM บน iOS ยังไม่รองรับการคำนวณผ่าน GPU/Metal โดยสมบูรณ์ การสั่ง `.gpu` ทั้งหมดจึงสร้าง Conversation ล้มเหลว
* **การแก้ไข**: ตรวจสอบให้มั่นใจว่าตั้งค่าใน `InferenceEngine.swift` แยก GPU ออกเป็นแบบ Hybrid:
  ```swift
  let config = try EngineConfig(
      modelPath: url.path,
      backend: .gpu,          // LLM ข้อความรันบน Metal (GPU)
      visionBackend: .cpu(),  // ส่วนจัดการภาพประมวลผลบน CPU
      maxNumTokens: 512,
      cacheDir: ...
  )
  ```

</details>

<details>
<summary><b>❌ [iOS] สแกนรูปภาพครั้งแรกผ่านสำเร็จ แต่ครั้งที่สองแล้วเกิดอาการเด้งดับ</b></summary>

* **สาเหตุ**: โมเดลตัวคูณประวัติบทสนทนา (KV cache) พยายามสะสมโทเค็นภาพเดิมรอบแรกและรูปภาพใหม่รวมเข้าด้วยกัน ทำให้ RAM เต็มกะทันหัน
* **การแก้ไข**: ระบบมีการย้ายมาใช้วิธี **One-shot Conversation recreation** ใน `InferenceEngine.swift` ซึ่งจะสร้าง Session ใหม่และลบทิ้งหลังประมวลผลเสร็จในแต่ละรอบโดยอัตโนมัติ ช่วยลดปัญหา OOM นี้ลงได้อย่างถาวร

</details>

---

## 🗺️ Roadmap

- [x] Phase 1 — Android Project Setup & ASR Configuration
- [x] Phase 2 — Android CameraX & Vulkan GPU VLM setup
- [x] Phase 3 — iOS platform research & XcodeGen framework setup
- [x] Phase 4 — iOS SwiftUI interface & Debug Console implementation
- [x] Phase 5 — iOS Apple Vision OCR (Thai + English) & CoreHaptics alerts
- [x] Phase 6 — iOS hybrid GPU/CPU execution structure
- [x] Phase 7 — iOS base64 raw data input pipeline
- [x] Phase 8 — iOS VLM Session memory reset to prevent OOM
- [ ] Phase 9 — End-to-end field testing with BLV users on both platforms
- [ ] Phase 10 — Multi-turn LoRA fine-tuning for specific navigation commands

---

<div align="center">

**Made with ❤️ for the Blind & Low Vision Community**

[![GitHub](https://img.shields.io/badge/GitHub-Bigzzz0-181717?style=flat-square&logo=github)](https://github.com/Bigzzz0/assistive-offline-ai)

</div>
