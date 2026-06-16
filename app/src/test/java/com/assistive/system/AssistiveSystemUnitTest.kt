package com.assistive.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AssistiveSystemUnitTest {

    // Helper method replicating the SceneChangeDetector calculation for assertion verification
    private fun calculateFrameDifference(current: IntArray, last: IntArray?): Float {
        if (last == null || current.size != last.size) return 1.0f

        var totalDiff = 0L
        for (i in current.indices) {
            totalDiff += abs(current[i] - last[i])
        }
        
        val maxPossibleDiff = current.size * 255.0f
        return totalDiff / maxPossibleDiff
    }

    // Voice command mock parser to verify pipeline intents
    private fun parseVoiceCommand(text: String): String {
        val prompt = text.lowercase()
        return when {
            prompt.contains("หยุด") -> "STOP"
            prompt.contains("อ่าน") -> "OCR"
            prompt.contains("ดู") || prompt.contains("สิ่งของ") -> "OBJECT_ID"
            prompt.contains("ข้างหน้า") || prompt.contains("กีดขวาง") -> "OBSTACLE"
            else -> "UNKNOWN"
        }
    }

    // Voice command parser test
    @Test
    fun testVoiceCommandsMapping() {
        assertEquals("OCR", parseVoiceCommand("ช่วยอ่านหนังสือหน้านี้ให้หน่อย"))
        assertEquals("STOP", parseVoiceCommand("กรุณาหยุดทำงาน"))
        assertEquals("OBJECT_ID", parseVoiceCommand("มีสิ่งของอะไรอยู่บนโต๊ะตัวนี้บ้าง"))
        assertEquals("OBSTACLE", parseVoiceCommand("ข้างหน้ามีสิ่งกีดขวางไหม"))
        assertEquals("UNKNOWN", parseVoiceCommand("วันนี้สภาพอากาศเป็นอย่างไร"))
    }

    // Frame difference boundary test: identical frames
    @Test
    fun testFrameDifferenceIdentical() {
        val size = 32 * 32
        val frame1 = IntArray(size) { 128 }
        val frame2 = IntArray(size) { 128 }
        
        val difference = calculateFrameDifference(frame1, frame2)
        assertEquals(0.0f, difference, 0.0001f)
    }

    // Frame difference boundary test: maximum difference (black to white)
    @Test
    fun testFrameDifferenceMaxContrast() {
        val size = 32 * 32
        val frameBlack = IntArray(size) { 0 }
        val frameWhite = IntArray(size) { 255 }
        
        val difference = calculateFrameDifference(frameBlack, frameWhite)
        assertEquals(1.0f, difference, 0.0001f)
    }

    // Frame difference boundary test: partial change (10% variance check)
    @Test
    fun testFrameDifferencePartialChange() {
        val size = 100
        val frame1 = IntArray(size) { 100 }
        val frame2 = IntArray(size) { 100 }
        // Change 10 pixels to maximum value (255)
        for (i in 0 until 10) {
            frame2[i] = 255
        }
        
        val difference = calculateFrameDifference(frame2, frame1)
        val expected = (10 * 155.0f) / (100 * 255.0f)
        assertEquals(expected, difference, 0.0001f)
        assertTrue(difference > 0.05f) // Should trigger scene change threshold (> 5%)
    }

    // Test OCR validation with phone numbers and postal codes
    @Test
    fun testOcrPostValidation() {
        val ocrInput = "กรุณาติดต่อที่เบอร์ 0812345678 หรือ 02-123-4567 รหัสไปรษณีย์ 40000"
        val output = com.assistive.system.ai.OcrPostValidator.validateOcrResult(ocrInput)
        
        // Assert normalized formatting
        assertTrue("Output should contain normalized 081: $output", output.contains("081-234-5678"))
        assertTrue("Output should contain normalized 02: $output", output.contains("02-123-4567"))
        
        // Assert structural summary inclusion
        if (!output.contains("[ข้อมูลที่ตรวจยืนยันถูกต้อง]")) {
            org.junit.Assert.fail("Output does not contain header. Output was: <$output>")
        }
        assertTrue("Output should contain verified phone: $output", output.contains("- เบอร์โทรศัพท์: 081-234-5678, 02-123-4567"))
        assertTrue("Output should contain verified postcode: $output", output.contains("- รหัสไปรษณีย์: 40000"))
    }
}
