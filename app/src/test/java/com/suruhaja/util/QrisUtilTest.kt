package com.suruhaja.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrisUtilTest {

    @Test
    fun staticCrcIsValid() {
        assertTrue(QrisUtil.isValidCrc(QrisUtil.STATIC_QRIS))
        val crc = QrisUtil.crc16(QrisUtil.STATIC_QRIS.dropLast(4))
        assertEquals("0288", crc)
    }

    @Test
    fun dynamicConversionHasValidCrc() {
        for (amt in listOf(7000L, 12000L, 25000L, 50000L)) {
            val dyn = QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, amt)
            assertTrue("CRC invalid for $amt", QrisUtil.isValidCrc(dyn))
        }
    }

    @Test
    fun dynamicMatchesKnownGoodValues() {
        // Nilai referensi dari validasi Node (qris-dinamis).
        assertEquals("BE47", QrisUtil.crc16(QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, 7000).dropLast(4)))
        assertEquals("D092", QrisUtil.crc16(QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, 12000).dropLast(4)))
        assertEquals("7167", QrisUtil.crc16(QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, 25000).dropLast(4)))
        assertEquals("4367", QrisUtil.crc16(QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, 50000).dropLast(4)))
    }

    @Test
    fun dynamicInjectsTag54AndFlipsMethod() {
        val dyn = QrisUtil.convertToDynamic(QrisUtil.STATIC_QRIS, 25000)
        // method 11 -> 12
        assertTrue(dyn.startsWith("000201010212"))
        // tag 54 = 25000 ada di string
        assertTrue(dyn.contains("540525000"))
    }
}
