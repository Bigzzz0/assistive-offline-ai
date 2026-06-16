package com.assistive.system.ai

import android.util.Log
import java.util.regex.Pattern

object OcrPostValidator {

    // Thai Mobile/Landline phone patterns: e.g. 081-234-5678, 02-123-4567, 0812345678
    private val phonePattern = Pattern.compile(
        """\b(0[1-9]\d{1,2})[-.\s]?(\d{3,4})[-.\s]?(\d{4})\b"""
    )

    // Thai Postal Code pattern (5 digits, e.g. 10100, 40000)
    private val postalCodePattern = Pattern.compile(
        """\b([1-9]\d{4})\b"""
    )

    // E-mail pattern
    private val emailPattern = Pattern.compile(
        """\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Z|a-z]{2,}\b"""
    )

    /**
     * Validates and enhances the text output of the OCR scan.
     * Searches for key information structures (phone, zip, email) and corrects
     * formatting or appends validation flags to help visually impaired users.
     */
    fun validateOcrResult(ocrText: String): String {
        var result = ocrText
        Log.d("OcrPostValidator", "Starting validation on: $result")

        // 1. Phone number post-validation & normalization
        val phoneMatcher = phonePattern.matcher(ocrText)
        val verifiedPhones = mutableListOf<String>()
        while (phoneMatcher.find()) {
            val fullMatch = phoneMatcher.group(0)
            val part1 = phoneMatcher.group(1)
            val part2 = phoneMatcher.group(2)
            val part3 = phoneMatcher.group(3)
            
            // Normalize spacing/dashes for screen readers
            val normalizedPhone = "$part1-$part2-$part3"
            verifiedPhones.add(normalizedPhone)
            
            // Highlight normalized phone number in the output text
            result = result.replace(fullMatch, normalizedPhone)
        }

        // 2. Postal code detection
        val postalMatcher = postalCodePattern.matcher(ocrText)
        val verifiedPostcodes = mutableListOf<String>()
        while (postalMatcher.find()) {
            val postcode = postalMatcher.group(1)
            verifiedPostcodes.add(postcode)
        }

        // 3. Email validation
        val emailMatcher = emailPattern.matcher(ocrText)
        val verifiedEmails = mutableListOf<String>()
        while (emailMatcher.find()) {
            val email = emailMatcher.group(0)
            verifiedEmails.add(email)
        }

        // Append structured validation summary at the end for accessibility enhancement
        if (verifiedPhones.isNotEmpty() || verifiedPostcodes.isNotEmpty() || verifiedEmails.isNotEmpty()) {
            val summary = StringBuilder("\n\n[ข้อมูลที่ตรวจยืนยันถูกต้อง]:")
            
            if (verifiedPhones.isNotEmpty()) {
                summary.append("\n- เบอร์โทรศัพท์: ${verifiedPhones.joinToString(", ")}")
            }
            if (verifiedPostcodes.isNotEmpty()) {
                summary.append("\n- รหัสไปรษณีย์: ${verifiedPostcodes.joinToString(", ")}")
            }
            if (verifiedEmails.isNotEmpty()) {
                summary.append("\n- อีเมล: ${verifiedEmails.joinToString(", ")}")
            }
            
            result += summary.toString()
        }

        return result
    }
}
